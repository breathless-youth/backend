package project.study.studysession.scheduler;

import io.sentry.Sentry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import project.study.common.NotFoundException;
import project.study.studysession.service.ActiveStudySessionService;
import project.study.studysession.service.InvalidSessionException;
import tools.jackson.core.JacksonException;

/**
 * 하트비트가 끊긴 진행중 세션(draft)을 자동 확정한다 (BY-447, ADR-0014).
 *
 * <p>예외를 직접 잡아 Sentry로 올린다 — @Scheduled 메서드에서 예외가 밖으로 나가면 Spring이
 * 로그만 남기고 삼켜서 Sentry에 아무것도 없다 (DailyReportScheduler와 동일).
 *
 * <p>폐기 여부는 예외 종류로 가른다. 검증 실패·유저 없음·역직렬화 불가는 재시도해도 영원히
 * 실패하는 영구 실패라 draft를 폐기한다. 그 외(커넥션 끊김, 락 타임아웃 등 일시 추정 오류)는
 * draft를 보존해 다음 틱에 자연 재시도되게 한다 — 확정은 멱등 수렴이라 재시도가 안전하고,
 * 여기서 함부로 폐기하면 이 기능이 막으려던 세션 유실을 서버가 대신 일으키게 된다(최종 리뷰 Important 2).
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
            } catch (InvalidSessionException | NotFoundException | JacksonException e) {
                log.error("진행중 세션 자동 확정 영구 실패 — draft를 폐기한다: draftId={}", draftId, e);
                Sentry.captureException(e);
                try {
                    activeStudySessionService.discardDraft(draftId);
                } catch (Exception discardEx) {
                    log.error("draft 폐기 자체가 실패했다 — draftId={}", draftId, discardEx);
                    Sentry.captureException(discardEx);
                }
            } catch (Exception e) {
                // 일시적 인프라 오류로 추정 — draft를 보존한다. 다음 틱에 last_seen_at 조건을 다시
                // 만족해 재시도되고, 확정이 멱등 수렴이라 여러 번 실패해도 안전하다
                log.error("진행중 세션 자동 확정 실패(일시적 추정) — draft를 보존하고 다음 틱에 재시도한다: draftId={}", draftId, e);
                Sentry.captureException(e);
            }
        }
    }
}
