package project.study.studysession.service;

import io.sentry.Sentry;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import project.study.studysession.entity.StudySession;
import project.study.studysession.entity.TimeSlot;
import project.study.studysession.repository.StudySessionRepository;

/**
 * V26 이전에 저장된 세션의 시간대 구간 행을 기동 때 채운다 (BY-828). 시간대 랭킹은 일·주뿐이라 귀속 날짜 기준 지난 주
 * 월요일부터의 세션만 있으면 된다 — 이번 주 판과 직전 주(offset=-1) 판이 모두 완전해진다(월요일 04시 전의 심야판 포함).
 * 출시 뒤엔 모든 새 세션에 행이 있어 조회 한 번으로 끝난다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SessionSlotBackfill implements ApplicationRunner {

    private final StudySessionRepository studySessionRepository;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;

    /** 실패해도 기동은 막지 않는다 — 시간대 값이 비는 것보다 서버가 안 뜨는 게 더 나쁘다. */
    @Override
    public void run(ApplicationArguments args) {
        try {
            Integer filled = transactionTemplate.execute(status -> backfill());
            if (filled != null && filled > 0) {
                log.info("시간대 구간 백필: {}개 세션", filled);
            }
        } catch (RuntimeException e) {
            log.error("시간대 구간 백필 실패", e);
            Sentry.captureException(e);
        }
    }

    /**
     * 구간 행이 없는 세션만 조회되므로 다시 돌려도 같은 결과다. 트랜잭션 안에서만 부른다(변경 감지로 저장). 분할은 순수
     * 계산이라 세션 하나가 깨져도 트랜잭션은 롤백 표시되지 않는다 — 그 세션만 건너뛰고 나머지는 채운다.
     */
    private int backfill() {
        List<StudySession> sessions = studySessionRepository.findSlotlessSince(fromDate(clock.instant()));
        int filled = 0;
        for (StudySession session : sessions) {
            try {
                session.attachSlots(SlotSplitter.split(
                        session.getStartedAt(), session.getEndedAt(), session.getFocusSec(), session.getEvents()));
                filled++;
            } catch (RuntimeException e) {
                log.warn("시간대 구간 백필에서 세션을 건너뜀: sessionId={}", session.getId(), e);
                Sentry.captureException(e);
            }
        }
        return filled;
    }

    /**
     * 백필을 시작할 날짜 — 시간대 귀속 날짜({@link TimeSlot#slotDateOf})로 본 지난 주 월요일. 심야판의 현재 기간은 월요일
     * 04시 전까지 일요일 귀속이라 그 직전 주(offset=-1)가 달력 기준보다 한 주 앞서고, 다른 판은 달력 날짜가 귀속일보다 늦거나
     * 같아 이 날짜로 모두 덮인다.
     */
    static LocalDate fromDate(Instant now) {
        return TimeSlot.slotDateOf(now)
                .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                .minusDays(7);
    }
}
