package project.study.metrics.report.render;

import java.util.List;
import project.study.metrics.slack.SlackMessage;

/** Slack에 보낼 본 메시지 하나와 스레드 댓글들. */
public record RenderedReport(SlackMessage summary, List<SlackMessage> threads) {}
