package project.study.config;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
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
 * dev 프로필 전용 목데이터 시더 — 데모 유저와 최근 5일치 공부 세션 5건을 시딩한다.
 * 서비스 레이어를 그대로 통과시켜 검증·계산·자정 분할이 실제 제출과 동일하게 적용되고,
 * 재시작할 때마다 오늘 기준 날짜로 갈아끼워 하루 조회 API에서 항상 데이터가 보인다.
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

        Instant now = clock.instant();
        LocalDate today = now.atZone(KST).toLocalDate();

        // 1) 오늘: 3시간 전~1시간 전 2시간 세션 — 미래 시각 검증을 피하려고 현재 시각 기준으로 잡는다
        Instant s1 = now.minus(Duration.ofHours(3));
        submit(
                userId,
                s1,
                now.minus(Duration.ofHours(1)),
                List.of(
                        event(EventStatus.PHONE, s1.plus(Duration.ofMinutes(30)), s1.plus(Duration.ofMinutes(40))),
                        event(EventStatus.AWAY, s1.plus(Duration.ofMinutes(70)), s1.plus(Duration.ofMinutes(80)))));

        // 2) 어제 14~17시: DEVICE 20분 + STOP 10분
        Instant s2 = kst(today.minusDays(1), 14);
        submit(
                userId,
                s2,
                kst(today.minusDays(1), 17),
                List.of(
                        event(EventStatus.DEVICE, s2.plus(Duration.ofMinutes(60)), s2.plus(Duration.ofMinutes(80))),
                        event(EventStatus.STOP, s2.plus(Duration.ofMinutes(120)), s2.plus(Duration.ofMinutes(130)))));

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

        log.info("dev 목데이터 시딩 완료 — 데모 userId={} (deviceId={}), 최근 5일 세션 5건 제출", userId, DEMO_DEVICE_ID);
    }

    private void submit(Long userId, Instant startedAt, Instant endedAt, List<StatusEventRequest> events) {
        // studySec/focusSec는 요청값이 그대로 저장되므로, 앱이 보내듯 값을 계산해 보낸다.
        // studySec은 STOP(일시정지) 구간만 빼고, focusSec은 전체 이벤트 구간을 뺀다.
        long totalSec = Duration.between(startedAt, endedAt).toSeconds();
        long stopSec = events.stream()
                .filter(event -> event.status() == EventStatus.STOP)
                .mapToLong(event ->
                        Duration.between(event.startedAt(), event.endedAt()).toSeconds())
                .sum();
        long nonFocusSec = events.stream()
                .mapToLong(event ->
                        Duration.between(event.startedAt(), event.endedAt()).toSeconds())
                .sum();
        int studySec = (int) (totalSec - stopSec);
        int focusSec = (int) (totalSec - nonFocusSec);
        studySessionService.create(
                new StudySessionCreateRequest(userId, startedAt, endedAt, studySec, focusSec, events));
    }

    private static StatusEventRequest event(EventStatus status, Instant startedAt, Instant endedAt) {
        return new StatusEventRequest(status, startedAt, endedAt);
    }

    private static Instant kst(LocalDate date, int hour) {
        return date.atStartOfDay(KST).plusHours(hour).toInstant();
    }
}
