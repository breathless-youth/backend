package project.study.group.service;

import io.sentry.Sentry;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import project.study.block.service.BlockService;
import project.study.group.push.GroupNotifications;
import project.study.group.repository.GroupAlertRepository;
import project.study.group.repository.GroupAlertRepository.AlertGroup;
import project.study.group.repository.GroupAlertRepository.StudyStart;
import project.study.group.repository.GroupDeskQueries;
import project.study.group.repository.GroupDeskQueries.MemberProfile;
import project.study.group.repository.GroupMemberRepository;
import project.study.group.repository.GroupMemberRepository.MemberRow;

/**
 * 그룹 공부방에서 실제 공부를 시작하면 해당 그룹의 알림을 켠 멤버에게 푸시를 보낸다 (BY-1382).
 * 시작 건마다 트랜잭션을 나눠 하나가 실패해도 나머지는 처리한다. 푸시는 수신 기록 커밋 뒤 발송한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GroupStartAlerts {

    private static final String UNKNOWN_NICKNAME = "새 사용자";

    private final GroupMemberRepository members;
    private final GroupAlertRepository alerts;
    private final GroupDeskQueries deskQueries;
    private final GroupNotifications notifications;
    private final BlockService blocks;
    private final TransactionTemplate tx;

    public int process(Instant now) {
        int sent = 0;
        for (StudyStart start : alerts.claimStudyStarts()) {
            try {
                Integer pushed = tx.execute(status -> alertGroup(start, now));
                sent += pushed == null ? 0 : pushed;
            } catch (Exception e) {
                log.error("멤버 공부 시작 알림 처리 실패 roomId={}, starterId={}", start.roomId(), start.starterId(), e);
                Sentry.captureException(e);
            }
        }
        return sent;
    }

    private int alertGroup(StudyStart start, Instant now) {
        AlertGroup group = alerts.findStudyGroup(start).orElse(null);
        if (group == null) {
            return 0;
        }
        if (!group.studying()) {
            alerts.releaseStudyStart(start.participationId());
            return 0;
        }
        long starterId = start.starterId();
        List<Long> candidates = members.findLiveByGroups(List.of(group.id())).stream()
                .filter(MemberRow::notifyEnabled)
                .map(MemberRow::userId)
                .filter(userId -> userId != starterId)
                .toList();
        if (candidates.isEmpty()) {
            return 0;
        }
        Set<Long> seated = Set.copyOf(alerts.findSeatHolders(start.roomId()));
        Set<Long> blocked = blocks.blockedPeers(starterId, candidates);
        List<Long> receivers = candidates.stream()
                .filter(userId -> !seated.contains(userId) && !blocked.contains(userId))
                .toList();
        List<Long> claimed = alerts.claim(receivers, group.id(), now);
        if (!claimed.isEmpty()) {
            notifications.memberStarted(claimed, group.id(), group.name(), nicknameOf(starterId));
        }
        return claimed.size();
    }

    private String nicknameOf(long userId) {
        MemberProfile profile = deskQueries.profiles(List.of(userId)).get(userId);
        return profile == null || profile.nickname() == null ? UNKNOWN_NICKNAME : profile.nickname();
    }
}
