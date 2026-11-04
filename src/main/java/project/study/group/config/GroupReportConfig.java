package project.study.group.config;

import java.util.concurrent.Executors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;
import project.study.group.report.ReportAlert;

/**
 * 신고 운영 알림 발송기를 만든다 (BY-1124). 봇 토큰과 Slack 주소는 일일 지표 리포트와 같은 값을 읽고, 채널만 신고용이다. 슬랙 호출은 가상 스레드
 * 실행기로 넘겨 요청 스레드를 붙잡지 않는다 — 실행기를 빈으로 내놓지 않는 이유는 {@link GroupPushConfig}와 같다.
 */
@Configuration
public class GroupReportConfig {

    @Bean
    ReportAlert reportAlert(
            RestClient.Builder restClientBuilder,
            @Value("${metrics.slack.base-url:https://slack.com}") String baseUrl,
            @Value("${metrics.slack.bot-token:}") String botToken,
            @Value("${app.group.report.slack-channel-id:}") String channelId) {
        return new ReportAlert(
                restClientBuilder, baseUrl, botToken, channelId, Executors.newVirtualThreadPerTaskExecutor());
    }
}
