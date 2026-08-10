package project.study.config;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import project.study.studysession.dto.StatusEventRequest;
import project.study.studysession.dto.StudySessionCreateRequest;
import project.study.studysession.entity.EventStatus;
import project.study.studysession.repository.StudySessionRepository;
import project.study.studysession.service.StudySessionService;
import project.study.user.dto.UserRegisterRequest;
import project.study.user.service.UserService;

/**
 * dev 프로필 전용 목데이터 시더 — 데모 유저와 엣지케이스 세션 5건 + 최근 30일 하루 0~8개 랜덤 세션을 시딩한다.
 * 세션이 아예 없는 날도 섞여있어 스트릭이 끊기는 케이스, 빈 날짜 조회도 데모 데이터로 확인할 수 있다.
 * 서비스 레이어를 그대로 통과시켜 검증·계산·자정 분할이 실제 제출과 동일하게 적용되고,
 * 재시작할 때마다 서버 시작 시각 기준 날짜로 갈아끼워 하루 조회 API에서 항상 데이터가 보인다.
 * 시딩 내용은 OpenApiConfig의 dev 전용 안내와 함께 유지한다.
 */
@Slf4j
@Component
@Profile("dev")
@RequiredArgsConstructor
public class DevDataSeeder implements ApplicationRunner {

    /** 데모 유저의 고정 deviceId — POST /api/users 에 이 값으로 등록하면 같은 userId를 돌려받는다(멱등). */
    public static final String DEMO_DEVICE_ID = "00000000-0000-0000-0000-000000000001";

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    /** 랜덤 세션을 채울 기간(오늘 포함 최근 N일)과 하루 최대 세션 수. */
    private static final int SEED_DAYS = 60;

    private static final int MAX_SESSIONS_PER_DAY = 5;

    /** 고정 시드 — 날짜 기준(today)이 같으면 재실행해도 같은 데이터가 나와 시딩이 멱등으로 유지된다. */
    private static final long RANDOM_SEED = 20260726L;

    private record Range(Instant start, Instant end) {}

    private final UserService userService;
    private final StudySessionService studySessionService;
    private final StudySessionRepository studySessionRepository;
    private final Clock clock;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        Long userId =
                userService.register(new UserRegisterRequest(DEMO_DEVICE_ID)).userId();
        studySessionRepository.deleteByUserId(userId);

        // 서브초를 버려야 세션 1이 자정에 걸릴 때(01~03시 기동) 조각별 내림 초 합계가 원본 총초와 같아진다
        Instant now = clock.instant().truncatedTo(ChronoUnit.SECONDS);
        LocalDate today = now.atZone(KST).toLocalDate();

        List<Range> curatedRanges = seedCuratedSessions(userId, now, today);
        int generated = seedRandomDays(userId, today, curatedRanges);

        log.info(
                "dev 목데이터 시딩 완료 — 데모 userId={} (deviceId={}), 엣지케이스 5건 + 랜덤 {}건 제출", userId, DEMO_DEVICE_ID, generated);
    }

    /** 엣지케이스 세션 5건을 제출하고, 고정 시각 세션 2~5의 구간을 반환한다 — 랜덤 시딩이 이 구간을 피해 생성한다. */
    private List<Range> seedCuratedSessions(Long userId, Instant now, LocalDate today) {
        // 1) 오늘: 3시간 전~1시간 전 2시간 세션 — 미래 시각 검증을 피하려고 현재 시각 기준으로 잡는다
        Instant s1 = now.minus(Duration.ofHours(3));
        submit(
                userId,
                s1,
                now.minus(Duration.ofHours(1)),
                List.of(
                        event(EventStatus.PHONE, s1.plus(Duration.ofMinutes(30)), s1.plus(Duration.ofMinutes(40))),
                        event(EventStatus.AWAY, s1.plus(Duration.ofMinutes(70)), s1.plus(Duration.ofMinutes(80)))));

        // 2) 어제 14~17시: DEVICE 20분 + PAUSE 10분
        Instant s2 = kst(today.minusDays(1), 14);
        submit(
                userId,
                s2,
                kst(today.minusDays(1), 17),
                List.of(
                        event(EventStatus.DEVICE, s2.plus(Duration.ofMinutes(60)), s2.plus(Duration.ofMinutes(80))),
                        event(EventStatus.PAUSE, s2.plus(Duration.ofMinutes(120)), s2.plus(Duration.ofMinutes(130)))));

        // 3) 2일 전 20:00~21:30: 이벤트 없음 (집중률 100%)
        Instant s3 = kst(today.minusDays(2), 20);
        submit(userId, s3, s3.plus(Duration.ofMinutes(90)), List.of());

        // 4) 3일 전 23시~2일 전 1시: 자정을 넘겨 두 세션으로 분할 저장, PHONE 20분이 자정에 10분씩 걸침
        Instant midnight = today.minusDays(2).atStartOfDay(KST).toInstant();
        submit(
                userId,
                midnight.minus(Duration.ofHours(1)),
                midnight.plus(Duration.ofHours(1)),
                List.of(event(
                        EventStatus.PHONE,
                        midnight.minus(Duration.ofMinutes(10)),
                        midnight.plus(Duration.ofMinutes(10)))));

        // 5) 4일 전 09:00~09:45: PHONE 5분
        Instant s5 = kst(today.minusDays(4), 9);
        submit(
                userId,
                s5,
                s5.plus(Duration.ofMinutes(45)),
                List.of(event(EventStatus.PHONE, s5.plus(Duration.ofMinutes(10)), s5.plus(Duration.ofMinutes(15)))));

        return List.of(
                new Range(s2, kst(today.minusDays(1), 17)),
                new Range(s3, s3.plus(Duration.ofMinutes(90))),
                new Range(midnight.minus(Duration.ofHours(1)), midnight.plus(Duration.ofHours(1))),
                new Range(s5, s5.plus(Duration.ofMinutes(45))));
    }

    /**
     * 어제부터 {@value SEED_DAYS}일 전까지 하루 0~8개 세션을 생성해 제출하고 제출 건수를 반환한다.
     * 0개인 날은 그대로 세션 없는 날로 남는다 — 스트릭이 끊기는 케이스와 빈 날짜 조회를 데모로 보여준다.
     * 오늘은 세션 1이 현재 시각 기준이라 결과가 매번 달라져(멱등 깨짐) 랜덤 시딩에서 제외한다.
     * 07~21시 창을 세션 수만큼 슬롯으로 나눠 슬롯당 하나씩 배치하므로 세션끼리 겹치지 않고 자정도 넘지 않는다.
     * 창을 21시에 끊는 이유: 오늘 세션(현재 시각-3h~-1h)이 자정 직후 기동 시 어제 21시 이후로 걸칠 수 있어서다.
     */
    private int seedRandomDays(Long userId, LocalDate today, List<Range> curatedRanges) {
        Random random = new Random(RANDOM_SEED);
        int generated = 0;
        for (int daysAgo = 1; daysAgo < SEED_DAYS; daysAgo++) {
            LocalDate date = today.minusDays(daysAgo + 4);
            int targetCount = random.nextInt(MAX_SESSIONS_PER_DAY + 1);
            int toGenerate = Math.max(0, targetCount - countPortionsOn(curatedRanges, date));
            if (toGenerate == 0) {
                continue;
            }

            Instant windowStart = kst(date, 7);
            long slotMin = Duration.ofHours(14).toMinutes() / toGenerate;
            for (int i = 0; i < toGenerate; i++) {
                long maxDurMin = Math.min(slotMin - 5, 180);
                long durMin = 15 + random.nextInt((int) (maxDurMin - 15 + 1));
                long offsetMin = random.nextInt((int) (slotMin - durMin + 1));
                Instant start = windowStart.plus(Duration.ofMinutes(slotMin * i + offsetMin));
                Instant end = start.plus(Duration.ofMinutes(durMin));
                List<StatusEventRequest> events = randomEvents(random, start, durMin);
                if (overlapsAny(curatedRanges, start, end)) {
                    continue;
                }
                submit(userId, start, end, events);
                generated++;
            }
        }
        return generated;
    }

    /** 30분 이상 세션에 0~2개 이벤트를 만든다. i번째 이벤트를 세션 i번째 반쪽 구간에 가두어 서로 겹치지 않는다. */
    private static List<StatusEventRequest> randomEvents(Random random, Instant sessionStart, long durMin) {
        if (durMin < 30) {
            return List.of();
        }
        int count = random.nextInt(3);
        List<StatusEventRequest> events = new ArrayList<>();
        long halfMin = durMin / 2;
        for (int i = 0; i < count; i++) {
            EventStatus[] statuses = EventStatus.values();
            EventStatus status = statuses[random.nextInt(statuses.length)];
            long eventDurMin = 3 + random.nextInt(8);
            long offsetMin = i * halfMin + random.nextInt((int) (halfMin - eventDurMin + 1));
            Instant eventStart = sessionStart.plus(Duration.ofMinutes(offsetMin));
            events.add(event(status, eventStart, eventStart.plus(Duration.ofMinutes(eventDurMin))));
        }
        return events;
    }

    /** 해당 날짜(KST)에 걸치는 구간 수 — 자정을 넘는 구간은 양쪽 날짜에서 각각 센다(자정 분할 저장과 동일 기준). */
    private static int countPortionsOn(List<Range> ranges, LocalDate date) {
        Instant dayStart = date.atStartOfDay(KST).toInstant();
        Instant dayEnd = date.plusDays(1).atStartOfDay(KST).toInstant();
        return (int) ranges.stream()
                .filter(range -> range.start().isBefore(dayEnd) && dayStart.isBefore(range.end()))
                .count();
    }

    private static boolean overlapsAny(List<Range> ranges, Instant start, Instant end) {
        return ranges.stream()
                .anyMatch(range -> start.isBefore(range.end()) && range.start().isBefore(end));
    }

    private void submit(Long userId, Instant startedAt, Instant endedAt, List<StatusEventRequest> events) {
        // studySec/focusSec는 요청값이 그대로 저장되므로, 앱이 보내듯 값을 계산해 보낸다.
        // studySec은 PAUSE(일시정지) 구간만 빼고, focusSec은 전체 이벤트 구간을 뺀다.
        long totalSec = Duration.between(startedAt, endedAt).toSeconds();
        long pauseSec = events.stream()
                .filter(event -> event.status() == EventStatus.PAUSE)
                .mapToLong(event ->
                        Duration.between(event.startedAt(), event.endedAt()).toSeconds())
                .sum();
        long nonFocusSec = events.stream()
                .mapToLong(event ->
                        Duration.between(event.startedAt(), event.endedAt()).toSeconds())
                .sum();
        int studySec = (int) (totalSec - pauseSec);
        int focusSec = (int) (totalSec - nonFocusSec);
        studySessionService.create(
                userId, new StudySessionCreateRequest(startedAt, endedAt, studySec, focusSec, events));
    }

    private static StatusEventRequest event(EventStatus status, Instant startedAt, Instant endedAt) {
        return new StatusEventRequest(status, startedAt, endedAt);
    }

    private static Instant kst(LocalDate date, int hour) {
        return date.atStartOfDay(KST).plusHours(hour).toInstant();
    }
}
