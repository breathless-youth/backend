package project.study.metrics.report.render;

import io.sentry.Sentry;
import java.util.List;
import java.util.function.Function;
import lombok.extern.slf4j.Slf4j;
import project.study.metrics.report.DailyReport;
import project.study.metrics.report.SectionResult;
import project.study.metrics.slack.SlackMessage;

/** 섹션 결과 → 본 메시지 + 스레드 6개. 렌더링 버그도 그 스레드만 "계산 실패"로 막는다 (스펙 §10). */
@Slf4j
public final class ReportRenderer {

    /**
     * Slack 메시지 하나의 대체 글(text) 상한. 블록 전체 길이 상한은 문서에 없고 rich_text 블록 JSON 약 1만 6천 자에서 거절된 보고가
     * 있어(msg_blocks_too_long) 넉넉히 낮춘다 — 넘는 스레드는 여러 댓글로 나눠 보낸다.
     */
    public static final int MAX_CHARS = 6_000;

    private static final String FAILED = "계산 실패 — Sentry 확인";

    private ReportRenderer() {}

    public static RenderedReport render(DailyReport report) {
        List<SlackMessage> threads = List.of(
                thread(report.signup(), "🕐 가입", SignupRenderer::render),
                thread(report.retention(), "📈 다시 와서 공부한 비율", RetentionRenderer::render),
                thread(report.segment(), "👥 첫 주에 며칠 공부했나 → 둘째 주에도 공부했나", SegmentRenderer::render),
                thread(report.roster(), "🙋 첫 주에 3일 이상 공부한 사람", RosterRenderer::render),
                thread(report.explorer(), "🔍 남는 사람과 관련 깊은 첫 주 행동 Top 3", ExplorerRenderer::render),
                thread(report.active(), "🧑‍🎓 어제 공부한 사람", ActiveUsersRenderer::render));
        // 본 메시지는 스레드를 달 하나여야 해서 자르고, 스레드는 나눠서 빠짐없이 보낸다
        return new RenderedReport(
                SummaryRenderer.render(report).cap(MAX_CHARS),
                threads.stream()
                        .flatMap(thread -> thread.split(MAX_CHARS).stream())
                        .toList());
    }

    private static <T> SlackMessage thread(SectionResult<T> result, String header, Function<T, SlackMessage> renderer) {
        SlackMessage failed = SlackMessage.builder(header).item(FAILED).build();
        try {
            return result.value().map(renderer).orElse(failed);
        } catch (RuntimeException e) {
            log.error("일일 리포트 스레드 렌더링 실패 (스레드={})", header, e);
            Sentry.captureException(e);
            return failed;
        }
    }
}
