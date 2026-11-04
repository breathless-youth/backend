package project.study.room.websocket;

import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import project.study.block.service.BlockService;
import project.study.room.dto.RoomMember;

/**
 * 그룹 공부방에서 차단한 사이를 알리는 {@code PEER_BLOCKED} (BY-1124, websocket.html §11.4). SNAPSHOT을 세션에 보낼 때마다(입장 확정·스냅샷
 * 재요청) 그 SNAPSHOT 멤버 중 어느 쪽이든 차단한 사이인 사람마다 그 세션에 이어서 보낸다 — 신고 순간의 {@code PEER_BLOCKED}를 구독 순서 때문에
 * 놓쳤어도 다음 SNAPSHOT이 다시 실어 준다. 입장 확정 때는 먼저 와 있던 상대에게도 새로 들어온 사람을 알린다.
 *
 * <p>초대코드 방은 보지 않는다 — 호출자가 {@link #isGroupRoom}으로 먼저 거르고, 거른 뒤에만 이 빈을 부른다(초대코드 방은 지금 그대로).
 */
@Component
@RequiredArgsConstructor
public class PeerBlocks {

    private final BlockService blocks;
    private final RoomMessenger messenger;

    /**
     * SNAPSHOT 멤버가 그룹 공부방 멤버인가. 멤버 조회 문장이 그룹 공부방에서만 {@code micOn}을 채우므로(초대코드 방은 null) 그것으로 가린다 — 방
     * 종류를 알려고 한 번 더 조회하지 않는다. SNAPSHOT에는 늘 나 자신이 있어 비어 있지 않다.
     */
    public static boolean isGroupRoom(List<RoomMember> members) {
        return !members.isEmpty() && members.getFirst().micOn() != null;
    }

    /** 입장 확정 SNAPSHOT 뒤 — 그 세션에 차단한 사이마다 보내고, 그 상대들의 모든 세션에는 새로 들어온 나를 같은 모양으로 알린다. */
    public void afterConfirmSnapshot(String userName, String sessionId, long userId, List<RoomMember> members) {
        for (long peer : toSession(userName, sessionId, userId, members)) {
            messenger.peerBlocked(peer, userId);
        }
    }

    /** 스냅샷 재요청 뒤 — 그 세션에만 다시 보낸다. 상대에게는 입장 확정 때 이미 보냈다. */
    public void afterResentSnapshot(String userName, String sessionId, long userId, List<RoomMember> members) {
        toSession(userName, sessionId, userId, members);
    }

    // SNAPSHOT 순서대로 보내고 보낸 상대를 돌려준다
    private List<Long> toSession(String userName, String sessionId, long userId, List<RoomMember> members) {
        List<Long> others = members.stream()
                .map(RoomMember::userId)
                .filter(id -> id != userId)
                .toList();
        Set<Long> blocked = blocks.blockedPeers(userId, others);
        List<Long> peers = others.stream().filter(blocked::contains).toList();
        peers.forEach(peer -> messenger.peerBlocked(userName, sessionId, peer));
        return peers;
    }
}
