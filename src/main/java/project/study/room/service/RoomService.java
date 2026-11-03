package project.study.room.service;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import project.study.common.exception.BadRequestException;
import project.study.common.exception.ConflictException;
import project.study.common.exception.ErrorCode;
import project.study.common.exception.NotFoundException;
import project.study.group.dto.GroupRoomJoinResponse;
import project.study.room.dto.RoomCreateResponse;
import project.study.room.dto.RoomJoinResponse;
import project.study.room.dto.RoomMember;
import project.study.room.entity.LeaveReason;
import project.study.room.repository.GroupSeatRepository;
import project.study.room.repository.GroupSeatRepository.GroupFlags;
import project.study.room.repository.RoomParticipationRepository;
import project.study.room.repository.RoomParticipationRepository.Profile;
import project.study.room.repository.RoomParticipationRepository.Row;
import project.study.room.repository.RoomRepository;
import project.study.room.repository.RoomRepository.RoomRow;

/**
 * 룸 멤버십 (스펙 §2.1~§2.6). 진실 원천은 DB다.
 *
 * <p>락 순서: 유저 advisory → 방 행(id 오름차순) → 참가자 행 → 코드 advisory(닫을 때만). 락을 잡기 전에 읽은
 * 값은 판단에 쓰지 않는다 — 방 락을 잡은 뒤 참가자 행을 다시 잠가 읽고 그 값으로 분기한다. 이것이 옛 전역
 * synchronized 락이 주던 "읽기와 쓰기 사이에 아무도 끼어들지 않음"을 대신한다. 모든 갱신은 건수를 확인한다.
 *
 * <p>그룹 공부방(BY-1124)도 같은 예약·유예 복귀·방 전환을 쓰고, 방을 코드 대신 그룹으로 찾는다. 그룹 쪽 락 순서(① 그룹 행 → ② 그룹 사용자
 * advisory → ③ 위의 유저 advisory → ④ 방 행 → ⑤ 참가자 행)에서 이 클래스는 ③ 이후만 잡는다 — 그룹 행을 쥔 호출자 안에서 불려도 거꾸로 잡지
 * 않는다.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RoomService {

    public static final int MAX_PARTICIPANTS = 6;
    public static final int EMPTY_ROOM_TTL_SECONDS = RoomCleanupService.EMPTY_ROOM_TTL_SECONDS;
    public static final int CLOSED_CODE_TTL_SECONDS = 600;
    private static final int INVITE_CODE_MAX_ATTEMPTS = 100;
    private static final SecureRandom RANDOM = new SecureRandom();

    public record JoinResult(RoomJoinResponse response, AutoLeave autoLeave) {}

    public record LeaveResult(boolean removed, boolean roomStillOpen) {
        public static final LeaveResult NONE = new LeaveResult(false, false);
    }

    /** 그룹 공부방 자리 예약 결과 — autoLeave는 {@link JoinResult}와 같다 (BY-1124). */
    public record GroupRoomJoin(GroupRoomJoinResponse response, AutoLeave autoLeave) {}

    /**
     * 찾은 그룹 공부방이 잠그기 전에 닫혔다(마지막 사람이 막 나감). 트랜잭션을 통째로 롤백하고 처음부터 다시 하라는 신호다 — 같은 트랜잭션에서 다시 열면
     * 이미 잠근 지금 방을 쥔 채 다른 멤버가 막 넣은 새 방을 기다리게 되고, 그 멤버가 같은 지금 방을 기다리면 교착이다.
     */
    public static final class GroupRoomClosedException extends RuntimeException {
        public GroupRoomClosedException(long groupId) {
            super("그룹 공부방이 잠그기 전에 닫힘: groupId=" + groupId);
        }
    }

    /** 응답 모양과 상관없는 자리 잡기 결과. graceRow는 유예 복귀일 때만 있다(이전 표시값을 이어 주려고). */
    private record Seat(Long roomId, Row graceRow, AutoLeave autoLeave) {}

    private final RoomRepository rooms;
    private final RoomParticipationRepository participations;
    private final GroupSeatRepository groupSeats;
    private final ParticipantRemover remover;
    private final TurnCredentialIssuer turnCredentials;
    private final TransactionTemplate tx;
    private final Clock clock;

    // 생성만으로는 입장 상태가 아니다 — 생성자도 join으로만 입장한다. 시도마다 짧은 트랜잭션(코드 락 + INSERT 한 문장)
    public RoomCreateResponse create(Long userId) {
        Instant now = clock.instant();
        Instant tombstoneCutoff = now.minusSeconds(CLOSED_CODE_TTL_SECONDS);
        for (int attempt = 0; attempt < INVITE_CODE_MAX_ATTEMPTS; attempt++) {
            String code = String.format("%04d", RANDOM.nextInt(10000));
            Optional<Long> roomId = tx.execute(status -> rooms.insertIfCodeFree(code, userId, now, tombstoneCutoff));
            if (roomId != null && roomId.isPresent()) {
                return new RoomCreateResponse(roomId.get(), code, EMPTY_ROOM_TTL_SECONDS);
            }
        }
        throw new ConflictException("사용 가능한 초대코드가 없습니다");
    }

    // 자리 예약만 하고 30초 안에 STOMP 구독으로 확정한다 (스펙 §2.2)
    @Transactional
    public JoinResult join(Long userId, String inviteCode, String nickname, String goal, String category) {
        if (inviteCode == null || !inviteCode.matches("\\d{4}")) {
            throw new BadRequestException("초대코드는 숫자 4자리여야 합니다");
        }
        Instant now = clock.instant();
        Profile profile = new Profile(nickname, goal, category);

        rooms.lockUser(userId);
        RoomRow target = findJoinableRoom(inviteCode, now);
        Optional<Long> currentRoomId = participations.findLiveRoomIdOfUser(userId);
        Map<Long, RoomRow> locked = lockRooms(target.id(), currentRoomId);
        RoomRow lockedTarget = locked.get(target.id());
        if (lockedTarget == null || !lockedTarget.isOpen()) {
            throw new NotFoundException(ErrorCode.ROOM_CLOSED, "방이 종료되었어요"); // 조회와 락 사이에 닫힘
        }

        Seat seat = seat(lockedTarget, locked, userId, profile, now);
        Row grace = seat.graceRow();
        return new JoinResult(
                response(seat.roomId(), grace != null, grace == null ? null : grace.cameraOn(), userId),
                seat.autoLeave());
    }

    /**
     * 그룹 공부방 자리 예약 (BY-1124). 초대코드 join과 같은 예약·유예 복귀·방 전환이고, 열린 방이 없으면 연다. 호출자가 그룹 행 {@code FOR
     * SHARE}·멤버 확인을 하고 같은 트랜잭션으로 부른다. 유예 복귀면 카메라·마이크·휴식을 이어 주고, 새 자리는 마이크·휴식이 꺼진 채(DB 기본값)
     * 시작한다.
     *
     * <p>찾은 방이 잠그기 전에 마지막 사람이 나가 닫혔으면 {@link GroupRoomClosedException}을 던진다 — 초대코드 방은 이때 ROOM_CLOSED지만 그룹
     * 공부방은 언제든 새로 열 수 있어, 호출자가 트랜잭션을 새로 열어 다시 부른다. 방을 열 때(넣기)는 아직 어떤 방 행도 쥐고 있지 않다.
     */
    @Transactional
    public GroupRoomJoin joinGroupRoom(long userId, long groupId, String nickname, String goal, String category) {
        Instant now = clock.instant();
        Profile profile = new Profile(nickname, goal, category);

        rooms.lockUser(userId);
        Long targetId =
                rooms.openGroupRoom(groupId, userId, now).orElseThrow(() -> new GroupRoomClosedException(groupId));
        Map<Long, RoomRow> locked = lockRooms(targetId, participations.findLiveRoomIdOfUser(userId));
        RoomRow target = locked.get(targetId);
        if (target == null || !target.isOpen()) {
            throw new GroupRoomClosedException(groupId);
        }
        return groupRoomJoin(seat(target, locked, userId, profile, now), userId);
    }

    private GroupRoomJoin groupRoomJoin(Seat seat, long userId) {
        Row grace = seat.graceRow();
        GroupFlags flags = grace == null ? null : groupSeats.flags(grace.id());
        GroupRoomJoinResponse response = new GroupRoomJoinResponse(
                seat.roomId(),
                grace != null,
                grace == null ? null : grace.cameraOn(),
                flags == null ? null : flags.micOn(),
                flags == null ? null : flags.onBreak(),
                turnCredentials.forUser(userId),
                turnCredentials.ttlSeconds());
        return new GroupRoomJoin(response, seat.autoLeave());
    }

    // 대상 방과 지금 방을 잠근 뒤 자리를 잡는다. 락 아래에서 다시 읽는다 — 이 값만 판단에 쓴다
    private Seat seat(RoomRow target, Map<Long, RoomRow> locked, Long userId, Profile profile, Instant now) {
        Optional<Row> current = participations.lockLiveOfUser(userId);
        if (current.isPresent() && current.get().roomId().equals(target.id())) {
            return rejoin(current.get(), target, profile, now);
        }
        current.ifPresent(row -> {
            if (!locked.containsKey(row.roomId())) {
                throw new IllegalStateException("유저 락 아래에서 잠그지 않은 방의 자리가 나타남: userId=" + userId);
            }
        });
        return takeSeat(target, current, locked, userId, profile, now);
    }

    private RoomRow findJoinableRoom(String inviteCode, Instant now) {
        RoomRow room = rooms.findLatestByCode(inviteCode)
                .orElseThrow(() -> new NotFoundException(ErrorCode.INVITE_CODE_NOT_FOUND, "코드를 다시 확인해 주세요"));
        if (room.isOpen()) {
            return room;
        }
        if (room.closedAt().plusSeconds(CLOSED_CODE_TTL_SECONDS).isAfter(now)) {
            throw new NotFoundException(ErrorCode.ROOM_CLOSED, "방이 종료되었어요");
        }
        throw new NotFoundException(ErrorCode.INVITE_CODE_NOT_FOUND, "코드를 다시 확인해 주세요");
    }

    private Map<Long, RoomRow> lockRooms(Long targetId, Optional<Long> currentRoomId) {
        List<Long> ids = Stream.concat(Stream.of(targetId), currentRoomId.stream())
                .distinct()
                .toList();
        return rooms.lockByIds(ids).stream().collect(Collectors.toMap(RoomRow::id, Function.identity()));
    }

    // 같은 방에 이미 자리가 있다 — 유예 복귀(끊김 상태) 또는 예약 재시도·확정 멤버의 중복 join
    private Seat rejoin(Row current, RoomRow room, Profile profile, Instant now) {
        if (current.disconnectedAt() != null) {
            expectOne(participations.restoreFromGrace(current.id(), profile, now), "유예 복귀");
            log.debug("재입장(유예 중 복원): roomId={}, userId={}", room.id(), current.userId());
            return new Seat(room.id(), current, null);
        }
        expectOne(participations.refreshReservation(current.id(), profile, now), "예약 갱신");
        return new Seat(room.id(), null, null);
    }

    // 정원 검사를 기존 방 퇴장보다 먼저 한다 — 대상 방이 가득이면 기존 방 자리를 잃지 않아야 한다
    private Seat takeSeat(
            RoomRow target,
            Optional<Row> current,
            Map<Long, RoomRow> locked,
            Long userId,
            Profile profile,
            Instant now) {
        if (participations.countLive(target.id()) >= MAX_PARTICIPANTS) {
            throw new ConflictException("방이 가득 찼어요");
        }
        AutoLeave autoLeave = null;
        if (current.isPresent()) {
            RoomRow oldRoom = locked.get(current.get().roomId());
            if (remover.remove(current.get(), oldRoom, LeaveReason.SWITCHED_ROOM, now, null)
                    .removed()) {
                autoLeave = new AutoLeave(oldRoom.id(), userId);
            }
        }
        participations.insertReservation(target.id(), userId, profile, now);
        log.debug("신규 입장 예약: roomId={}, userId={}, autoLeave={}", target.id(), userId, autoLeave);
        return new Seat(target.id(), null, autoLeave);
    }

    private RoomJoinResponse response(Long roomId, boolean graceRejoin, Boolean cameraOn, Long userId) {
        return new RoomJoinResponse(
                roomId, graceRejoin, cameraOn, turnCredentials.forUser(userId), turnCredentials.ttlSeconds());
    }

    @Transactional
    public LeaveResult leave(Long roomId, Long userId) {
        Optional<RoomRow> room = rooms.lockById(roomId).filter(RoomRow::isOpen);
        if (room.isEmpty()) {
            return LeaveResult.NONE;
        }
        Optional<Row> row = participations.lockLive(roomId, userId);
        if (row.isEmpty()) {
            return LeaveResult.NONE;
        }
        var removed = remover.remove(row.get(), room.get(), LeaveReason.EXPLICIT, clock.instant(), null);
        log.debug("퇴장 요청: roomId={}, userId={}, 처리됨={}", roomId, userId, removed.removed());
        return new LeaveResult(removed.removed(), !removed.roomClosed());
    }

    /**
     * 그룹 쪽 변화(내보내기·그룹 나가기·그룹 사라짐)로 이 그룹 공부방의 자리를 지운다 (BY-1124). 호출자의 그룹 트랜잭션 안에서 ④ 방 행 → ⑤ 자리
     * 행만 잡는다 — 같은 자리를 두고 겨루는 join(방 전환)·정리 작업과는 이 두 락이 직렬화한다. 지운 자리가 있던 방 ID를 돌려주고, 이 그룹 공부방에
     * 자리가 없었으면 비어 있다(다른 방의 자리는 건드리지 않는다).
     */
    @Transactional
    public Optional<Long> removeFromGroupRoom(long groupId, long userId, LeaveReason reason, Instant now) {
        Optional<RoomRow> room = rooms.lockOpenByGroup(groupId);
        if (room.isEmpty()) {
            return Optional.empty();
        }
        Optional<Row> row = participations.lockLive(room.get().id(), userId);
        if (row.isEmpty()) {
            return Optional.empty();
        }
        boolean removed =
                remover.remove(row.get(), room.get(), reason, now, null).removed();
        log.debug("그룹 공부방 자리 정리: groupId={}, userId={}, 사유={}, 처리됨={}", groupId, userId, reason, removed);
        return removed ? Optional.of(room.get().id()) : Optional.empty();
    }

    /** 그룹의 열린 공부방 ID. 잠그지 않는다. */
    @Transactional(readOnly = true)
    public Optional<Long> openGroupRoomId(long groupId) {
        return rooms.findOpenByGroup(groupId).map(RoomRow::id);
    }

    /** 두 사람이 같은 그룹 공부방에 자리가 있는가(예약만 한 자리 포함, BY-1124). 잠그지 않는다. 초대코드 방은 세지 않는다. */
    @Transactional(readOnly = true)
    public boolean inSameGroupRoom(long userId, long otherUserId) {
        return groupSeats.shareGroupRoom(userId, otherUserId);
    }

    /**
     * STOMP 구독으로 자리를 확정한다. 0행이면 빈 목록(방·참가자 없음, 또는 더 늦게 열린 세션이 이미 확정됨).
     * 방 락은 잡지 않는다 — 인원이 안 바뀐다. join이 이 행을 잠그고 있으면 그 커밋 뒤에 적용된다.
     */
    @Transactional
    public List<RoomMember> confirmStomp(
            Long roomId, Long userId, String sessionId, Instant sessionOpenedAt, String taskId) {
        int confirmed = participations.confirm(roomId, userId, sessionId, sessionOpenedAt, taskId, clock.instant());
        if (confirmed == 0) {
            log.debug("STOMP 확정 실패(방/참가자 없음 또는 옛 세션): roomId={}, userId={}", roomId, userId);
            return List.of();
        }
        log.debug("STOMP 확정: roomId={}, userId={}, stompSessionId={}", roomId, userId, sessionId);
        return participations.findConfirmedMembers(roomId);
    }

    /** 끊김 — 세션 ID로 바로 찾는다. 재접속이 먼저 도착했으면 0행(옛 세션의 뒤늦은 끊김)이라 무시된다. */
    @Transactional
    public boolean handleDisconnect(String sessionId) {
        if (sessionId == null) {
            return false;
        }
        boolean started = participations.markDisconnected(sessionId, clock.instant()) == 1;
        log.debug("연결 해제: stompSessionId={}, 유예 시작={}", sessionId, started);
        return started;
    }

    @Transactional(readOnly = true)
    public boolean roomExists(Long roomId) {
        return rooms.isOpen(roomId);
    }

    private static void expectOne(int updated, String what) {
        if (updated != 1) {
            throw new IllegalStateException(what + " 갱신 행 수가 1이 아님: " + updated);
        }
    }
}
