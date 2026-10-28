package project.study.push.service;

import io.sentry.Sentry;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import project.study.metrics.report.fact.ExperimentData;
import project.study.push.dto.PushMessage;
import project.study.push.dto.PushResult;
import project.study.push.repository.PushExperimentQueries;

/**
 * 첫 세션 5분 유도 푸시 A/B 실험 (BY-1070, ADR-0037). 가입했지만 첫 세션을 켜지 않은 사람을 push·control로 반씩 나누고
 * push에게만 가입 N시간 뒤(D0)와 다음 날 20시(D1)에 보낸다. 판정은 일일 리포트가 한다.
 *
 * <p>후보 창이 틱 간격과 같아 한 사람은 한 틱에서만 잡힌다. 서버가 멈춰 창을 놓친 단계는 따라잡지 않는다 — 늦은 알림은 실험
 * 일정을 흐린다.
 */
@Slf4j
@Service
public class FirstSessionPushExperiment {

    public static final String EXPERIMENT = "first_session_5min";
    public static final String ARM_PUSH = "PUSH";
    public static final String ARM_CONTROL = "CONTROL";
    static final String TYPE_D0 = "first_session_d0";
    static final String TYPE_D1 = "first_session_d1";

    /** 스케줄러 주기와 같아야 후보가 빠지거나 겹치지 않는다. */
    static final Duration TICK = Duration.ofMinutes(10);

    static final LocalTime QUIET_FROM = LocalTime.of(22, 0);
    static final LocalTime QUIET_UNTIL = LocalTime.of(8, 0);
    static final LocalTime D1_AT = LocalTime.of(20, 0);
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private static final PushContent D0 = new PushContent("5분 집중 습관 만들기 🔥", "지금 5분만 켜두고 가볍게 시작해보세요!");
    private static final PushContent D1 = new PushContent("딱 5분만 집중해볼까요? 👀", "시작이 반이에요. 오늘 하루 끝나기 전에 가볍게 타이머를 시작해봐요!");

    // 집단 배정용 — 예측 가능한 배정이 실험을 기울이지 않게 RoomService 초대 코드와 같은 생성기를 쓴다
    private static final SecureRandom RANDOM = new SecureRandom();

    private final PushExperimentQueries queries;
    private final PushSender pushSender;
    private final Duration d0Delay;
    private final AtomicBoolean warnedDisabled = new AtomicBoolean();

    public FirstSessionPushExperiment(
            PushExperimentQueries queries,
            PushSender pushSender,
            @Value("${app.push.first-session-experiment.d0-delay:PT2H}") Duration d0Delay) {
        this.queries = queries;
        this.pushSender = pushSender;
        this.d0Delay = d0Delay;
    }

    public void tick(Instant now) {
        // 틱 시각을 10분 격자로 반올림한다 — 스케줄러가 몇 초~몇 분 늦게 돌아도 창이 빠지거나 겹치지 않는다(5분 넘게 밀리면 깨진다)
        Instant slot =
                Instant.ofEpochSecond(Math.round(now.getEpochSecond() / (double) TICK.toSeconds()) * TICK.toSeconds());
        ZonedDateTime kst = slot.atZone(KST);
        LocalTime time = kst.toLocalTime();
        if (!time.isBefore(QUIET_FROM) || time.isBefore(QUIET_UNTIL)) {
            return;
        }
        // 키가 없으면 발송은 0건인데 배정과 발송 기록만 남아, 알림을 못 받은 사람이 발송 집단에 섞인다
        if (!pushSender.isEnabled()) {
            if (warnedDisabled.compareAndSet(false, true)) {
                log.warn("FCM 자격증명이 없어 첫 세션 푸시 실험을 멈춘다. 배정과 발송 기록을 남기지 않는다");
            }
            return;
        }
        LocalDate today = kst.toLocalDate();
        Instant startOfToday = today.atStartOfDay(KST).toInstant();

        // D0: 가입 + N이 지난 한 틱 안에 들고, 가입일이 오늘인 사람
        Instant d0To = slot.minus(d0Delay);
        Instant d0From = max(d0To.minus(TICK), startOfToday);
        if (d0From.isBefore(d0To)) {
            run(queries.findFirstSessionCandidates(d0From, d0To), TYPE_D0, D0, today, now);
        }
        // D1: 20시 틱에 어제 가입한 사람
        if (time.equals(D1_AT)) {
            Instant yesterday = today.minusDays(1).atStartOfDay(KST).toInstant();
            run(queries.findFirstSessionCandidates(yesterday, startOfToday), TYPE_D1, D1, today, now);
        }
    }

    /**
     * 일일 리포트용 배정 사실. 읽기 전에 첫 세션·D7 칸을 채운다 — 발송을 끈 뒤에도 결과는 계속 채워져야 해서 발송 작업이 아니라
     * 리포트가 부른다.
     */
    public ExperimentData findFacts() {
        queries.fillFirstSessions(EXPERIMENT);
        queries.fillDaySeven(EXPERIMENT);
        return new ExperimentData(d0Delay, queries.findFacts(EXPERIMENT));
    }

    // 한 사람의 실패가 나머지를 막지 않는다
    private void run(List<Long> userIds, String type, PushContent content, LocalDate today, Instant now) {
        for (long userId : userIds) {
            try {
                String arm = queries.assign(EXPERIMENT, userId, RANDOM.nextBoolean() ? ARM_PUSH : ARM_CONTROL, now);
                if (ARM_PUSH.equals(arm)) {
                    send(userId, type, content, today, now);
                }
            } catch (RuntimeException e) {
                log.error("첫 세션 실험 처리 실패 (userId={}, type={})", userId, type, e);
                Sentry.captureException(e);
            }
        }
    }

    // 자리를 먼저 잡아, 다른 태스크가 같은 틱에 돌거나 FCM 호출이 실패해도 그날 다시 보내지 않는다
    private void send(long userId, String type, PushContent content, LocalDate today, Instant now) {
        UUID pushId = UUID.randomUUID();
        if (!queries.reserveSend(pushId, userId, type, today, now)) {
            return;
        }
        PushResult result =
                pushSender.send(List.of(userId), new PushMessage(content.title(), content.body(), "/", type, pushId));
        queries.recordAccepted(pushId, result.sent());
    }

    private static Instant max(Instant a, Instant b) {
        return a.isAfter(b) ? a : b;
    }

    private record PushContent(String title, String body) {}
}
