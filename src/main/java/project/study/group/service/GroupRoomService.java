package project.study.group.service;

import java.time.Clock;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import project.study.common.tx.AfterCommit;
import project.study.group.dto.GroupRoomJoinResponse;
import project.study.room.entity.LeaveReason;
import project.study.room.service.AutoLeave;
import project.study.room.service.RoomService;
import project.study.room.service.RoomService.GroupRoomClosedException;
import project.study.room.service.RoomService.GroupRoomJoin;
import project.study.room.service.RoomService.LeaveResult;
import project.study.room.websocket.RoomMessageType;
import project.study.room.websocket.RoomMessenger;
import project.study.user.dto.ProfileResponse;
import project.study.user.service.UserService;

/**
 * 그룹 공부방 (BY-1124) — 그룹 쪽 입구. 자리·예약·정리는 초대코드 방과 같은 {@link RoomService}에 맡기고, 여기서는 그룹 자격을 보고 그룹 쪽 변화를
 * 공부방 메시지로 옮긴다. STOMP는 모두 커밋 뒤에 보낸다({@link AfterCommit}) — 롤백되면 아무것도 나가지 않는다.
 *
 * <p>락 순서: ① 그룹 행 → ③ 룸 사용자 락 → ④ 방 행 → ⑤ 자리 행. 자리 잡기·나가기는 ①을 {@code FOR SHARE}로 잡고, 내보내기·나가기·삭제는 ①을
 * {@code FOR UPDATE}로 쥔 호출자가 {@link #memberRemoved}·{@link #ownerChanged}를 부른다(이 둘은 ④⑤만 잡는다).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class GroupRoomService {

    // 닫힘 경합은 마지막 사람이 나가는 순간에만 생긴다 — 두 번째 시도는 새 방을 열므로 세 번이면 넉넉하다
    private static final int JOIN_ATTEMPTS = 3;

    private final GroupAccess access;
    private final RoomService roomService;
    private final UserService userService;
    private final RoomMessenger messenger;
    private final TransactionTemplate tx;
    private final Clock clock;

    /**
     * 자리 잡기 — 멤버만(없음 404, 비멤버 403). 다른 방에 있었으면 그 방에서 나오고, 커밋 뒤 그 방에 {@code MEMBER_LEFT}를 알린다. 방 안에 보일
     * 닉네임·목표는 지금 프로필이다.
     *
     * <p>찾은 공부방이 잠그기 전에 닫혔으면({@link GroupRoomClosedException}) 트랜잭션을 통째로 롤백해 쥔 락을 모두 놓고 새 트랜잭션으로 다시 한다 —
     * 두 번째에는 새 방을 열거나 다른 멤버가 막 연 방에 앉는다. 롤백된 시도의 커밋 뒤 작업은 버려진다.
     */
    public GroupRoomJoinResponse join(long userId, long groupId) {
        for (int attempt = 1; ; attempt++) {
            try {
                return tx.execute(status -> joinOnce(userId, groupId));
            } catch (GroupRoomClosedException e) {
                if (attempt >= JOIN_ATTEMPTS) {
                    throw e;
                }
                log.debug("그룹 공부방이 잠그기 전에 닫힘 — 다시 시도: groupId={}, userId={}, attempt={}", groupId, userId, attempt);
            }
        }
    }

    private GroupRoomJoinResponse joinOnce(long userId, long groupId) {
        access.requireMemberForShare(groupId, userId);
        ProfileResponse profile = userService.getProfile(userId);
        GroupRoomJoin result =
                roomService.joinGroupRoom(userId, groupId, profile.nickname(), profile.goal(), profile.category());
        AutoLeave autoLeave = result.autoLeave();
        if (autoLeave != null) {
            AfterCommit.run(() -> messenger.broadcast(autoLeave.roomId(), memberLeft(autoLeave.userId())));
        }
        return result.response();
    }

    /** 나가기 — 멤버만. 이 그룹 공부방에 내 자리가 없으면 아무것도 하지 않는다. 남은 사람이 있으면 커밋 뒤 {@code MEMBER_LEFT}. */
    @Transactional
    public void leave(long userId, long groupId) {
        access.requireMemberForShare(groupId, userId);
        Optional<Long> roomId = roomService.openGroupRoomId(groupId);
        if (roomId.isEmpty()) {
            return;
        }
        LeaveResult result = roomService.leave(roomId.get(), userId);
        if (result.removed() && result.roomStillOpen()) {
            AfterCommit.run(() -> messenger.broadcast(roomId.get(), memberLeft(userId)));
        }
    }

    /**
     * 멤버가 그룹을 떠났다(내보내짐·나감·그룹 사라짐) — 그 그룹 공부방에 자리가 있었으면 지운다. 호출자의 그룹 트랜잭션 안에서만 부른다(그룹 행을 이미
     * 잠갔다). 커밋 뒤 내보내진 경우에만 그 사람의 모든 세션에 {@code KICKED}를, 이어서 방 토픽에 {@code MEMBER_LEFT}를 보낸다. 자리가 없었으면
     * 아무것도 보내지 않는다.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void memberRemoved(long groupId, long userId, boolean kicked) {
        LeaveReason reason = kicked ? LeaveReason.KICKED : LeaveReason.GROUP_LEFT;
        Optional<Long> roomId = roomService.removeFromGroupRoom(groupId, userId, reason, clock.instant());
        if (roomId.isEmpty()) {
            return;
        }
        // 따로 등록한다 — KICKED 발송이 실패해도 MEMBER_LEFT는 나가야 다른 멤버 화면에서 빠진다
        if (kicked) {
            AfterCommit.run(
                    () -> messenger.toUser(userId, Map.of("type", RoomMessageType.KICKED.name(), "groupId", groupId)));
        }
        AfterCommit.run(() -> messenger.broadcast(roomId.get(), memberLeft(userId)));
    }

    /** 그룹장이 바뀌었다 — 공부방이 열려 있으면 커밋 뒤 방 토픽에 {@code OWNER_CHANGED}. 호출자의 그룹 트랜잭션 안에서만 부른다. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void ownerChanged(long groupId, long newOwnerId) {
        roomService
                .openGroupRoomId(groupId)
                .ifPresent(roomId -> AfterCommit.run(() -> messenger.broadcast(
                        roomId, Map.of("type", RoomMessageType.OWNER_CHANGED.name(), "userId", newOwnerId))));
    }

    /**
     * 두 사람 사이에 막 차단이 생겼다 — 둘 다 같은 그룹 공부방에 자리가 있으면(예약만 한 자리 포함) 커밋 뒤 각자의 모든 세션에 상대를 알리는
     * {@code PEER_BLOCKED}를 보낸다. 양쪽이 같은 모양이라 누가 차단했는지 드러나지 않는다. 지금 같은 방이 아니면 보내지 않는다 — 나중에 같은 방에
     * 들어오면 입장 확정 SNAPSHOT 뒤에 나간다. 호출자의 트랜잭션 안에서만 부른다.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void peerBlocked(long userId, long otherUserId) {
        if (!roomService.inSameGroupRoom(userId, otherUserId)) {
            return;
        }
        AfterCommit.run(() -> messenger.peerBlocked(userId, otherUserId));
        AfterCommit.run(() -> messenger.peerBlocked(otherUserId, userId));
    }

    private static Map<String, Object> memberLeft(long userId) {
        return Map.of("type", RoomMessageType.MEMBER_LEFT.name(), "userId", userId);
    }
}
