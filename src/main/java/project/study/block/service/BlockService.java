package project.study.block.service;

import java.time.Instant;
import java.util.Collection;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import project.study.block.dto.BlockListResponse;
import project.study.block.dto.BlockListResponse.BlockedUser;
import project.study.block.repository.BlockRepository;
import project.study.user.image.ProfileImageUrls;

/**
 * 사용자 차단 (BY-1124). 차단은 공부방 멤버 신고로만 생기고, 그룹과 상관없이 사용자 사이에 걸린다. 막는 쪽(공부방 SIGNAL·{@code PEER_BLOCKED}·
 * 멤버 공부 시작 알림)은 모두 "어느 쪽이든 차단한 사이"를 본다 — 내가 풀어도 상대가 건 차단이 남아 있으면 계속 막힌다.
 *
 * <p>차단을 풀어도 이미 끊은 공부방 연결을 다시 잇게 알리지 않는다. 다음 입장부터 {@code PEER_BLOCKED}가 오지 않고 SIGNAL이 다시 오간다.
 */
@Service
@RequiredArgsConstructor
public class BlockService {

    private final BlockRepository blocks;
    private final ProfileImageUrls profileImageUrls;

    /** 내가 건 차단 목록 — 오래된 것부터. 나를 차단한 사람은 싣지 않는다. */
    @Transactional(readOnly = true)
    public BlockListResponse blocks(Long userId) {
        return new BlockListResponse(blocks.findByBlocker(userId).stream()
                .map(row -> new BlockedUser(
                        row.userId(), row.nickname(), profileImageUrls.of(row.profileImage()), row.blockedAt()))
                .toList());
    }

    /** 내가 건 차단을 푼다. 차단하지 않은 사람이어도 그대로 끝난다(204). */
    @Transactional
    public void unblock(Long userId, long targetUserId) {
        blocks.delete(userId, targetUserId);
    }

    /** 차단을 건다. 이미 걸려 있으면 그대로다(처음 차단 시각 유지). */
    @Transactional
    public void block(long blockerId, long blockedId, Instant now) {
        blocks.insert(blockerId, blockedId, now);
    }

    @Transactional(readOnly = true)
    public boolean blockedEitherWay(long a, long b) {
        return blocks.existsEitherWay(a, b);
    }

    /** {@code others} 중 {@code userId}와 어느 쪽이든 차단한 사이인 사람. {@code others}가 비면 조회하지 않는다. */
    @Transactional(readOnly = true)
    public Set<Long> blockedPeers(long userId, Collection<Long> others) {
        if (others.isEmpty()) {
            return Set.of();
        }
        return blocks.findPeers(userId, others);
    }
}
