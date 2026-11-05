package project.study.group.service;

import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import project.study.common.category.StudyCategory;
import project.study.common.exception.ErrorCode;
import project.study.common.text.Graphemes;
import project.study.group.ApplyState;
import project.study.group.GroupJoinPolicy;
import project.study.group.dto.GroupMemberDesk;
import project.study.group.dto.PublicGroupDetailResponse;
import project.study.group.dto.PublicGroupDetailResponse.GroupOwnerProfile;
import project.study.group.dto.PublicGroupDetailResponse.PendingApplicationRef;
import project.study.group.dto.PublicGroupPageResponse;
import project.study.group.dto.PublicGroupPageResponse.PublicGroupCard;
import project.study.group.repository.GroupApplicationRepository;
import project.study.group.repository.GroupApplicationRepository.ApplicationRow;
import project.study.group.repository.GroupMemberRepository;
import project.study.group.repository.GroupRepository;
import project.study.group.repository.GroupRepository.GroupRow;
import project.study.group.repository.GroupRepository.PublicGroupRow;

/**
 * 공개 그룹 찾기 — 목록과 보기 (BY-1124). 둘 다 읽기뿐이라 REPEATABLE READ 읽기 트랜잭션 하나에서 읽는다 — 멤버 공부 상태와 이번 주 평균이
 * 확정 합계와 draft를 한 스냅샷에서 읽어야 하고({@code MemberStudySource}), 평균 캐시를 채우는 쪽이 이 트랜잭션에 합류한다.
 *
 * <p>평균·정렬은 {@link GroupWeeklyAverages}가 공개 그룹 전체를 한 번에 계산해 60초 캐시한 값을 쓴다. 책상은 다른 곳(싱글룸·다른 그룹 공부방)이
 * 어디인지 드러내지 않는다 — 이 그룹 공부방에 있을 때만 {@code IN_ROOM}이고 나머지는 앱 전체 상태({@code STUDYING}/{@code PAUSED}/{@code
 * IDLE}) 그대로다({@link GroupDeskReader}).
 */
@Service
@RequiredArgsConstructor
public class PublicGroupService {

    private final GroupRepository groups;
    private final GroupMemberRepository members;
    private final GroupApplicationRepository applications;
    private final GroupAccess access;
    private final GroupDeskReader deskReader;
    private final GroupWeeklyAverages weeklyAverages;
    private final Clock clock;

    /** {@link #applyDecision}의 결과 — 상태와 (UNAVAILABLE이면) 이유, (APPLIED이면) 내 신청. */
    private record ApplyDecision(ApplyState state, ErrorCode reason, PendingApplicationRef application) {

        static ApplyDecision of(ApplyState state) {
            return new ApplyDecision(state, null, null);
        }
    }

    /**
     * 공개 그룹 목록의 한 쪽. 이름 검색·시험 목표 거르기·정렬을 메모리에서 하고(공개 그룹 전체를 읽는다), 정렬은 다 차지 않은 그룹 → 평균 큰 순 →
     * 그룹 ID 작은 순이다. 카드의 공부 중 수는 그 쪽 그룹들의 책상만 읽어 센다.
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public PublicGroupPageResponse publicGroups(Long userId, String query, StudyCategory category, int page, int size) {
        Instant now = clock.instant();
        String needle = searchKey(Graphemes.normalize(query));
        Map<Long, Long> averages = weeklyAverages.averages(now);

        List<PublicGroupRow> matched = groups.findPublicLive().stream()
                .filter(group -> category == null || group.category() == category)
                .filter(group -> needle == null || searchKey(group.name()).contains(needle))
                .sorted(listOrder(averages))
                .toList();
        // page는 컨트롤러가 10000까지, size는 50까지로 막지만 곱이 int를 넘지 않게 long으로 센다
        long from = (long) page * size;
        List<PublicGroupRow> pageRows = matched.stream().skip(from).limit(size).toList();
        boolean hasNext = matched.size() > from + size;

        Set<Long> joined = Set.copyOf(members.findLiveGroupIdsOfUser(userId));
        List<GroupRow> pageGroups =
                groups.findLiveByIds(pageRows.stream().map(PublicGroupRow::id).toList());
        Map<Long, List<GroupMemberDesk>> desks = deskReader.desks(pageGroups, now);

        List<PublicGroupCard> cards = pageRows.stream()
                .map(group -> new PublicGroupCard(
                        group.id(),
                        group.name(),
                        group.category(),
                        group.joinPolicy(),
                        group.memberCount(),
                        averages.getOrDefault(group.id(), 0L),
                        GroupDeskReader.studyingCount(desks.getOrDefault(group.id(), List.of())),
                        joined.contains(group.id())))
                .toList();
        return new PublicGroupPageResponse(cards, hasNext);
    }

    /**
     * 공개 그룹 보기 — 멤버가 아니어도 볼 수 있다. 비공개·삭제·없음은 모두 404 {@code GROUP_NOT_FOUND}다. {@code applyState}는 MEMBER >
     * APPLIED > FULL > UNAVAILABLE > CAN_APPLY 순으로 먼저 맞는 것 하나이고, 이 응답은 안내일 뿐이라 신청할 때 {@link
     * GroupApplicationService#apply}가 락을 잡고 다시 확인한다.
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public PublicGroupDetailResponse publicGroup(Long userId, long groupId) {
        Instant now = clock.instant();
        GroupRow group = access.requirePublic(groupId);
        List<GroupMemberDesk> desks = deskReader.desks(List.of(group), now).get(groupId);
        // 그룹장은 늘 멤버다 — 책상에서 그룹장 줄을 꺼내 쓴다(GroupAccess.requireOwner와 같은 전제)
        GroupMemberDesk ownerDesk = desks.stream()
                .filter(GroupMemberDesk::owner)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("그룹장이 멤버가 아니다 groupId=" + groupId));
        boolean member = desks.stream().anyMatch(desk -> desk.userId() == userId);
        ApplyDecision decision = applyDecision(userId, group, member, desks.size(), now);
        return new PublicGroupDetailResponse(
                group.id(),
                group.name(),
                group.category(),
                group.joinPolicy(),
                group.description(),
                desks.size(),
                new GroupOwnerProfile(ownerDesk.userId(), ownerDesk.nickname(), ownerDesk.profileImageUrl()),
                weeklyAverages.averages(now).getOrDefault(groupId, 0L),
                desks,
                decision.state(),
                decision.reason(),
                decision.application());
    }

    private ApplyDecision applyDecision(long userId, GroupRow group, boolean member, int memberCount, Instant now) {
        if (member) {
            return ApplyDecision.of(ApplyState.MEMBER);
        }
        Optional<ApplicationRow> pending = group.joinPolicy() == GroupJoinPolicy.APPROVAL
                ? applications.findPending(group.id(), userId, now)
                : Optional.empty();
        if (pending.isPresent()) {
            ApplicationRow application = pending.get();
            return new ApplyDecision(
                    ApplyState.APPLIED, null, new PendingApplicationRef(application.id(), application.expiresAt()));
        }
        if (memberCount >= GroupService.MAX_MEMBERS) {
            return ApplyDecision.of(ApplyState.FULL);
        }
        ErrorCode reason = unavailableReason(userId, group, now);
        return reason == null
                ? ApplyDecision.of(ApplyState.CAN_APPLY)
                : new ApplyDecision(ApplyState.UNAVAILABLE, reason, null);
    }

    /** 신청할 수 없는 이유를 {@code RECENTLY_KICKED} > {@code GROUP_SLOT_LIMIT} > {@code APPLICATION_LIMIT} 순으로 하나. 없으면 null. */
    private ErrorCode unavailableReason(long userId, GroupRow group, Instant now) {
        if (members.recentlyKicked(group.id(), userId, now.minus(GroupService.KICK_COOLDOWN))) {
            return ErrorCode.RECENTLY_KICKED;
        }
        int pending = applications.countPendingOfUser(userId, now);
        if (members.countLiveGroupsOfUser(userId) + pending >= GroupService.MAX_GROUP_SLOTS) {
            return ErrorCode.GROUP_SLOT_LIMIT;
        }
        if (group.joinPolicy() == GroupJoinPolicy.APPROVAL && pending >= GroupService.MAX_PENDING_APPLICATIONS) {
            return ErrorCode.APPLICATION_LIMIT;
        }
        return null;
    }

    /** 다 찬 그룹은 평균이 커도 뒤로 간다(신청할 수 없는 그룹이 위에 오지 않게). 그 안에서 평균 큰 순, 같으면 ID 작은 순. */
    private static Comparator<PublicGroupRow> listOrder(Map<Long, Long> averages) {
        return Comparator.comparing((PublicGroupRow group) -> group.memberCount() >= GroupService.MAX_MEMBERS)
                .thenComparing(
                        (PublicGroupRow group) -> averages.getOrDefault(group.id(), 0L), Comparator.reverseOrder())
                .thenComparingLong(PublicGroupRow::id);
    }

    /** 이름 검색 비교용 글자 — 대소문자를 무시하고, {@code %}·{@code _}는 그냥 글자다. 비면 검색어 없음(null). */
    private static String searchKey(String normalized) {
        if (normalized == null || normalized.isEmpty()) {
            return null;
        }
        return normalized.toLowerCase(Locale.ROOT);
    }
}
