package project.study.group.service;

import io.sentry.Sentry;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import project.study.block.service.BlockService;
import project.study.group.push.GroupNotifications;
import project.study.group.repository.GroupAlertRepository;
import project.study.group.repository.GroupDeskQueries;
import project.study.group.repository.GroupDeskQueries.MemberProfile;
import project.study.group.repository.GroupMemberRepository;
import project.study.group.repository.GroupMemberRepository.MemberRow;
import project.study.group.repository.GroupRepository;
import project.study.group.repository.GroupRepository.GroupRow;
import project.study.studysession.dto.NewDraft;
import project.study.studysession.service.NewDraftSource;

/**
 * 멤버 공부 시작 알림 (BY-1124) — 막 시작한 사람이 든 그룹마다, 알림을 켠 다른 멤버에게 "공부를 시작했어요"를 보낸다. 스케줄러가 10초마다 부른다.
 * 체크포인트 요청은 이 일에 참여하지 않는다: 새 draft를 DB에서 꺼내 가는 쪽이 여기다.
 *
 * <p>순서: 새 draft를 한 번만 꺼낸다({@link NewDraftSource}) → 시작한 사람마다 별도 트랜잭션으로 그룹·멤버·자리를 읽고 → 받을 사람을 거른 뒤 →
 * "1시간에 한 번" 기록을 조건부로 잡아 돌려받은 사람에게만 커밋 뒤 푸시를 보낸다. 꺼내기와 사람별 처리를 나눈 것은 한 사람의 실패(데이터 이상,
 * 일시적 DB 오류)가 같은 틱의 다른 사람 알림을 막거나 매 틱 되풀이되는 독약 행이 되지 않게 하려는 것이다. 대신 꺼낸 뒤 실패한 사람의 알림은
 * 버려진다(최대 한 번) — 푸시는 최선 노력이고, 같은 시작을 두 번 알리는 쪽이 더 나쁘다.
 *
 * <p>푸시 발송은 {@link GroupNotifications}가 커밋 뒤 별도 실행기에서 하므로 이 트랜잭션을 붙잡지 않고, 발송 실패는 처리에 영향이 없다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GroupStartAlerts {

    private static final String UNKNOWN_NICKNAME = "새 사용자";

    private final NewDraftSource newDrafts;
    private final GroupMemberRepository members;
    private final GroupRepository groups;
    private final GroupAlertRepository alerts;
    private final GroupDeskQueries deskQueries;
    private final GroupNotifications notifications;
    private final BlockService blocks;
    private final TransactionTemplate tx;

    /** 새 draft를 처리하고 보낸 푸시 수(받는 사람 × 그룹)를 돌려준다. */
    public int process(Instant now) {
        List<NewDraft> drafts = newDrafts.claimNewDrafts(now);
        // 같은 사람의 새 draft가 한 틱에 둘이어도 한 번만 다룬다 (뒤의 것은 어차피 1시간 기록에 걸린다)
        Set<Long> starters = drafts.stream().map(NewDraft::userId).collect(Collectors.toCollection(LinkedHashSet::new));
        int sent = 0;
        for (long starterId : starters) {
            try {
                Integer pushed = tx.execute(status -> alertGroupsOf(starterId, now));
                sent += pushed == null ? 0 : pushed;
            } catch (Exception e) {
                log.error("멤버 공부 시작 알림 처리 실패 starterId={}", starterId, e);
                Sentry.captureException(e);
            }
        }
        return sent;
    }

    /** 시작한 사람이 든 그룹마다 받을 사람을 정해 푸시를 보낸다. 쿼리는 시작한 사람 한 명당 고정 횟수(그룹·받는 사람 수와 무관)다. */
    private int alertGroupsOf(long starterId, Instant now) {
        List<Long> joined = members.findLiveGroupIdsOfUser(starterId);
        if (joined.isEmpty()) {
            return 0;
        }
        // id 순이라 같은 사람을 다루는 다른 트랜잭션과 알림 기록 행을 같은 순서로 잡는다
        List<GroupRow> liveGroups = groups.findLiveByIds(joined);
        if (liveGroups.isEmpty()) {
            return 0;
        }
        List<Long> groupIds = liveGroups.stream().map(GroupRow::id).toList();
        List<MemberRow> everyone = members.findLiveByGroups(groupIds);
        // 알림은 기본이 꺼짐이라 받을 사람이 아예 없는 시작이 흔하다 — 그럴 땐 자리·차단·닉네임은 읽지 않는다
        Set<Long> candidates = everyone.stream()
                .filter(member -> member.notifyEnabled() && member.userId() != starterId)
                .map(MemberRow::userId)
                .collect(Collectors.toSet());
        if (candidates.isEmpty()) {
            return 0;
        }
        Map<Long, List<MemberRow>> membersByGroup =
                everyone.stream().collect(Collectors.groupingBy(MemberRow::groupId));
        Map<Long, Set<Long>> seated = alerts.findSeatHolders(groupIds);
        // 차단은 그룹과 상관없이 사람 사이에 걸려 그룹마다가 아니라 한 번에 읽는다
        Set<Long> blocked = blocks.blockedPeers(starterId, candidates);
        String nickname = nicknameOf(starterId);

        int sent = 0;
        for (GroupRow group : liveGroups) {
            List<Long> receivers = receiversOf(
                    starterId,
                    membersByGroup.getOrDefault(group.id(), List.of()),
                    seated.getOrDefault(group.id(), Set.of()),
                    blocked);
            List<Long> claimed =
                    alerts.claim(receivers, group.id(), now).stream().sorted().toList();
            if (claimed.isEmpty()) {
                continue;
            }
            notifications.memberStarted(claimed, group.id(), group.name(), nickname);
            sent += claimed.size();
        }
        return sent;
    }

    /**
     * 한 그룹에서 받을 사람 후보. 알림을 켠 다른 멤버 중 그 그룹 공부방에 자리가 없고, 시작한 사람과 어느 쪽으로도 차단한 사이가 아닌 사람이다.
     * 받을 사람을 거르는 자리는 여기 한 곳이다 — 거르는 단계는 "1시간에 한 번" 기록을 잡기 전에 끝나야 한다(걸러진 사람이 그 기록만 쓰고 푸시를
     * 못 받는 일이 없게).
     */
    private List<Long> receiversOf(long starterId, List<MemberRow> groupMembers, Set<Long> seated, Set<Long> blocked) {
        return groupMembers.stream()
                .filter(MemberRow::notifyEnabled)
                .map(MemberRow::userId)
                .filter(userId -> userId != starterId)
                .filter(userId -> !seated.contains(userId))
                .filter(userId -> !blocked.contains(userId))
                .toList();
    }

    private String nicknameOf(long userId) {
        MemberProfile profile = deskQueries.profiles(List.of(userId)).get(userId);
        return profile == null || profile.nickname() == null ? UNKNOWN_NICKNAME : profile.nickname();
    }
}
