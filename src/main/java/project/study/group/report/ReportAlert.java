package project.study.group.report;

import io.sentry.Sentry;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.Executor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import project.study.common.tx.AfterCommit;
import project.study.group.repository.ReportRepository;
import project.study.metrics.slack.SlackBotNotifier;
import project.study.metrics.slack.SlackMessage;
import project.study.metrics.slack.SlackNotifier;

/**
 * 신고 운영 알림 (BY-1124) — 신고 한 건마다 운영 슬랙 채널에 보낸다. 운영자는 이 알림과 DB/Metabase로 신고를 본다(관리 화면 없음).
 *
 * <p>일일 지표 리포트의 슬랙 봇 발송기({@link SlackBotNotifier})를 채널만 바꿔 따로 만들어 쓴다 — 토큰 처리·요청 한도(429) 기다림·토큰이 로그에
 * 새지 않게 하는 처리를 그대로 쓰면서, 지표 리포트의 빈과 설정은 건드리지 않는다. 채널({@code app.group.report.slack-channel-id})이나 토큰
 * ({@code metrics.slack.bot-token})이 비면 기동 때 경고를 한 번 남기고 아무것도 보내지 않는다 — 채널은 팀이 정해 알려 준다.
 *
 * <p>업무 트랜잭션이 커밋된 뒤에만({@link AfterCommit}) 실행기로 넘겨 보낸다 — 롤백된 신고는 알리지 않고, 슬랙 호출(429면 몇 초씩 기다린다)이
 * 요청 스레드를 붙잡지 않는다. 발송 실패는 로그와 Sentry에만 남긴다. 신고 행은 이미 커밋됐으므로 알림을 놓쳐도 DB에서 찾을 수 있다.
 */
@Slf4j
public class ReportAlert {

    /** 알릴 신고 한 건. 신청 ID는 신청 신고에만, 사유는 있을 때만 있다. */
    public record Report(
            String type,
            long reporterId,
            long targetUserId,
            long groupId,
            Long applicationId,
            String reason,
            Instant reportedAt) {}

    private static final DateTimeFormatter KST_TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss 'KST'").withZone(ZoneId.of("Asia/Seoul"));

    /** 꺼져 있으면 null. */
    private final SlackNotifier slack;

    private final Executor executor;

    public ReportAlert(
            RestClient.Builder restClientBuilder,
            String baseUrl,
            String botToken,
            String channelId,
            Executor executor) {
        this.executor = executor;
        this.slack = notifier(restClientBuilder, baseUrl, botToken, channelId);
        if (slack == null) {
            log.warn("app.group.report.slack-channel-id·metrics.slack.bot-token 중 비었거나 형식이 잘못된 값이 있어 신고 알림을 보내지 않는다");
        }
    }

    // 비었을 때는 발송기를 만들지 않는다 — 만들면 그쪽이 "일일 지표 리포트를 보내지 않는다"는 엉뚱한 경고를 남긴다
    private static SlackNotifier notifier(RestClient.Builder builder, String baseUrl, String token, String channel) {
        if (!StringUtils.hasText(token) || !StringUtils.hasText(channel)) {
            return null;
        }
        SlackBotNotifier notifier = new SlackBotNotifier(builder, baseUrl, token, channel);
        return notifier.isEnabled() ? notifier : null;
    }

    public boolean isEnabled() {
        return slack != null;
    }

    /** 커밋 뒤 실행기에서 신고 알림을 보낸다. 꺼져 있으면 아무것도 하지 않는다. */
    public void send(Report report) {
        if (slack == null) {
            return;
        }
        SlackMessage message = message(report);
        AfterCommit.run(() -> executor.execute(() -> deliver(message)));
    }

    private void deliver(SlackMessage message) {
        try {
            slack.post(message);
        } catch (Exception e) {
            log.error("신고 알림 발송 실패", e);
            Sentry.captureException(e);
        }
    }

    /** 사유는 사용자 입력이지만 rich_text 글자로 나가고 대체 글은 발송기가 이스케이프해, 멘션·링크가 되지 않는다. */
    static SlackMessage message(Report report) {
        SlackMessage.Builder builder = SlackMessage.builder("그룹 신고가 들어왔어요")
                .item("종류: ", label(report.type()))
                .item("신고한 사람: userId ", report.reporterId())
                .item("신고된 사람: userId ", report.targetUserId())
                .item("그룹: groupId ", report.groupId());
        if (report.applicationId() != null) {
            builder.item("신청: applicationId ", report.applicationId());
        }
        return builder.item("사유: ", report.reason() == null ? "(없음)" : report.reason())
                .item("시각: ", KST_TIME.format(report.reportedAt()))
                .build();
    }

    private static String label(String type) {
        return switch (type) {
            case ReportRepository.ROOM_MEMBER -> "공부방 멤버 신고 (" + type + ")";
            case ReportRepository.APPLICATION -> "신청 신고 (" + type + ")";
            default -> type;
        };
    }
}
