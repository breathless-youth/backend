package project.study.metrics.report.render;

import io.sentry.Sentry;
import java.util.List;
import java.util.function.Function;
import lombok.extern.slf4j.Slf4j;
import project.study.metrics.report.DailyReport;
import project.study.metrics.report.SectionResult;

/** 섹션 결과 → 본 메시지 + 스레드 6개. 렌더링 버그도 그 스레드만 "계산 실패"로 막는다 (스펙 §10). */
@Slf4j
public final class ReportRenderer {

    /** Slack 메시지 하나의 failsafe 상한 (기존 리포트와 같은 값). */
    public static final int MAX_CHARS = 40_000;

    private static final String FAILED = "계산 실패 — Sentry 확인";
    private static final String CAPPED = "\n…(길이 상한 40,000자로 잘림)";

    private ReportRenderer() {}

    public static RenderedReport render(DailyReport report) {
        List<String> threads = List.of(
                thread(report.signup(), "🕐 가입", SignupRenderer::render),
                thread(report.retention(), "📈 다시 와서 공부한 비율", RetentionRenderer::render),
                thread(report.segment(), "👥 첫 주에 며칠 공부했나 → 둘째 주에도 공부했나", SegmentRenderer::render),
                thread(report.roster(), "🙋 첫 주에 3일 이상 공부한 사람", RosterRenderer::render),
                thread(report.explorer(), "🔍 남는 사람과 관련 깊은 첫 주 행동 Top 3", ExplorerRenderer::render),
                thread(report.active(), "🧑‍🎓 어제 공부한 사람", ActiveUsersRenderer::render));
        return new RenderedReport(
                cap(SummaryRenderer.render(report)),
                threads.stream().map(ReportRenderer::cap).toList());
    }

    public static String cap(String text) {
        if (text.length() <= MAX_CHARS) {
            return text;
        }
        return text.substring(0, MAX_CHARS - CAPPED.length()) + CAPPED;
    }

    private static <T> String thread(SectionResult<T> result, String header, Function<T, String> renderer) {
        String failed = header + "\n" + FAILED;
        try {
            return result.value().map(renderer).orElse(failed);
        } catch (RuntimeException e) {
            log.error("일일 리포트 스레드 렌더링 실패 (스레드={})", header, e);
            Sentry.captureException(e);
            return failed;
        }
    }
}
