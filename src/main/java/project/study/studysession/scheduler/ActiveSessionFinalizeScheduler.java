package project.study.studysession.scheduler;

import io.sentry.Sentry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import project.study.studysession.service.ActiveStudySessionService;

/**
 * 하트비트가 끊긴 진행중 세션(draft)을 자동 확정한다 (BY-447, ADR-0014).
 *
 * <p>예외를 직접 잡아 Sentry로 올린다 — @Scheduled 메서드에서 예외가 밖으로 나가면 Spring이
 * 로그만 남기고 삼켜서 Sentry에 아무것도 남지 않는다 (DailyReportScheduler와 동일).
 *
 * <p>테스트에서는 session-finalize.enabled=false로 꺼서 백그라운드 확정이 테스트 데이터를
 * 건드리지 않게 한다 — 확정 로직 자체는 서비스 메서드 직접 호출로 검증한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "session-finalize.enabled", havingValue = "true", matchIfMissing = true)
public class ActiveSessionFinalizeScheduler {

    private final ActiveStudySessionService activeStudySessionService;

    @Scheduled(initialDelay = 60_000, fixedDelay = 60_000)
    public void finalizeStaleDrafts() {
        for (Long draftId : activeStudySessionService.findStaleDraftIds()) {
            try {
                activeStudySessionService.finalizeDraft(draftId);
            } catch (Exception e) {
                log.error("진행중 세션 자동 확정 실패 — draft를 폐기한다: draftId={}", draftId, e);
                Sentry.captureException(e);
                activeStudySessionService.discardDraft(draftId);
            }
        }
    }
}
