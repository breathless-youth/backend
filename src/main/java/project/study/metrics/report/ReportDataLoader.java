package project.study.metrics.report;

import static java.util.stream.Collectors.groupingBy;
import static java.util.stream.Collectors.toSet;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import project.study.dday.service.DdayMetricsService;
import project.study.metrics.amplitude.AmplitudeAttribute;
import project.study.metrics.amplitude.AmplitudeDailyCount;
import project.study.metrics.amplitude.AmplitudeQueryService;
import project.study.metrics.report.UserTimeline.Activity;
import project.study.metrics.report.fact.SessionFact;
import project.study.metrics.report.fact.SubjectFact;
import project.study.metrics.report.fact.UserEventFact;
import project.study.metrics.report.fact.UserFact;
import project.study.room.service.RoomMetricsService;
import project.study.studysession.service.StudySessionMetricsService;
import project.study.subject.service.SubjectMetricsService;
import project.study.user.service.UserService;

/**
 * 도메인 서비스에서 리포트 기간의 사실을 모아 유저별 타임라인으로 묶는다. metrics는 다른 도메인 테이블을 직접 읽지 않는다(스펙 §8).
 *
 * <p>원본 사실은 {@code [A−61, A]}, 가입자는 {@code [A−68, A]}(28~34일 뒤 리텐션의 지난주 코호트)다(스펙 §3).
 */
@Component
public class ReportDataLoader {

    static final int FACT_DAYS_BACK = 61;
    static final int SIGNUP_DAYS_BACK = 68;

    private final UserService userService;
    private final StudySessionMetricsService sessionMetricsService;
    private final RoomMetricsService roomMetricsService;
    private final SubjectMetricsService subjectMetricsService;
    private final DdayMetricsService ddayMetricsService;
    private final AmplitudeQueryService amplitudeQueryService;
    private final Set<Long> excludedUserIds;

    public ReportDataLoader(
            UserService userService,
            StudySessionMetricsService sessionMetricsService,
            RoomMetricsService roomMetricsService,
            SubjectMetricsService subjectMetricsService,
            DdayMetricsService ddayMetricsService,
            AmplitudeQueryService amplitudeQueryService,
            @Value("${metrics.report.excluded-user-ids:}") Set<Long> excludedUserIds) {
        this.userService = userService;
        this.sessionMetricsService = sessionMetricsService;
        this.roomMetricsService = roomMetricsService;
        this.subjectMetricsService = subjectMetricsService;
        this.ddayMetricsService = ddayMetricsService;
        this.amplitudeQueryService = amplitudeQueryService;
        this.excludedUserIds = Set.copyOf(excludedUserIds);
    }

    public ReportData load(LocalDate anchor) {
        LocalDate factFrom = anchor.minusDays(FACT_DAYS_BACK);
        // 어제 늦게 가입한 사람의 "가입 3시간 안 공부"가 오늘(A+1) 새벽으로 넘어가서 세션만 하루 더 읽는다
        List<SessionFact> sessions =
                kept(sessionMetricsService.findSessionFacts(factFrom, anchor.plusDays(1)), SessionFact::userId);
        Set<Long> activeIds = sessions.stream().map(SessionFact::userId).collect(toSet());
        List<UserFact> users = kept(
                userService.findReportUsers(Kst.startOf(anchor.minusDays(SIGNUP_DAYS_BACK)), activeIds),
                UserFact::userId);
        Set<Long> userIds = users.stream().map(UserFact::userId).collect(toSet());

        Map<Long, Map<Activity, List<Instant>>> activities =
                activities(Kst.startOf(factFrom), Kst.startOf(anchor.plusDays(1)), userIds);
        Map<Long, List<SubjectFact>> subjects =
                subjectMetricsService.findSubjectsOf(userIds).stream().collect(groupingBy(SubjectFact::userId));
        List<AmplitudeDailyCount> counts = amplitudeQueryService.findCounts(factFrom, anchor, AmplitudeKeys.ALL);
        Map<Long, List<AmplitudeDailyCount>> countsByUser =
                counts.stream().collect(groupingBy(AmplitudeDailyCount::userId));
        Map<Long, AmplitudeAttribute> attributes = amplitudeQueryService.findAttributes(userIds);
        Map<Long, List<SessionFact>> sessionsByUser = sessions.stream().collect(groupingBy(SessionFact::userId));

        List<UserTimeline> timelines = users.stream()
                .map(user -> new UserTimeline(
                        user,
                        sessionsByUser.getOrDefault(user.userId(), List.of()),
                        activities.getOrDefault(user.userId(), Map.of()),
                        subjects.getOrDefault(user.userId(), List.of()),
                        countsByUser.getOrDefault(user.userId(), List.of()),
                        attributes.get(user.userId())))
                .toList();
        return new ReportData(
                anchor, timelines, amplitudeQueryService.collectedDates(factFrom, anchor), appOpened(counts, anchor));
    }

    private Map<Long, Map<Activity, List<Instant>>> activities(Instant from, Instant to, Set<Long> userIds) {
        Map<Long, Map<Activity, List<Instant>>> byUser = new HashMap<>();
        add(byUser, Activity.ROOM_JOIN, roomMetricsService.findJoins(from, to));
        add(byUser, Activity.TASK_DONE, subjectMetricsService.findTasksDone(from, to));
        add(byUser, Activity.DDAY_SET, ddayMetricsService.findDdaysOf(userIds));
        return byUser;
    }

    private static void add(
            Map<Long, Map<Activity, List<Instant>>> byUser, Activity activity, List<UserEventFact> facts) {
        for (UserEventFact fact : facts) {
            byUser.computeIfAbsent(fact.userId(), id -> new EnumMap<>(Activity.class))
                    .computeIfAbsent(activity, key -> new ArrayList<>())
                    .add(fact.at());
        }
    }

    // Amplitude에는 타 환경·지운 행의 id가 섞일 수 있어 users에 있는 회원만 센다 (스펙 §7.4)
    private Set<Long> appOpened(List<AmplitudeDailyCount> counts, LocalDate anchor) {
        Set<Long> opened = counts.stream()
                .filter(count -> count.date().equals(anchor) && count.count() > 0)
                .filter(count -> AmplitudeKeys.APP_OPEN.contains(count.key()))
                .map(AmplitudeDailyCount::userId)
                .filter(userId -> !excludedUserIds.contains(userId))
                .collect(toSet());
        return opened.isEmpty() ? Set.of() : Set.copyOf(userService.findExistingUserIds(opened));
    }

    private <T> List<T> kept(List<T> facts, Function<T, Long> userId) {
        return facts.stream()
                .filter(fact -> !excludedUserIds.contains(userId.apply(fact)))
                .toList();
    }
}
