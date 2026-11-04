package project.study.group;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.client.RestClient;
import project.study.group.report.ReportAlert;
import project.study.group.report.ReportAlert.Report;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 신고 운영 알림 (BY-1124) — 신고 한 건마다 신고 채널로 슬랙 봇 메시지를 보낸다. 지표 리포트와 같은 봇 토큰을 쓰되 채널만 다르다. 채널이나 토큰이
 * 비면 보내지 않는다. 커밋 뒤에만, 요청 스레드가 아닌 실행기에서 보낸다. 가짜 Slack 서버로 실제 HTTP를 본다.
 */
class ReportAlertTest {

    private static final String TOKEN = "xoxb-0000-reportBotToken";
    private static final String CHANNEL = "C0REPORT1";
    private static final Executor INLINE = Runnable::run;
    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** 2026-11-03 14:30:05 KST */
    private static final Instant AT =
            ZonedDateTime.parse("2026-11-03T14:30:05+09:00[Asia/Seoul]").toInstant();

    private static final Report MEMBER_REPORT =
            new Report("ROOM_MEMBER", 12, 34, 7, null, "카메라를 켜고 <!channel> 했어요", AT);
    private static final Report APPLICATION_REPORT = new Report("APPLICATION", 12, 56, 7, 55L, null, AT);

    private final List<String> authorizations = new CopyOnWriteArrayList<>();
    private final List<JsonNode> bodies = new CopyOnWriteArrayList<>();
    private final AtomicInteger status = new AtomicInteger(200);
    private HttpServer server;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/chat.postMessage", exchange -> {
            try (exchange) {
                authorizations.add(exchange.getRequestHeaders().getFirst("Authorization"));
                bodies.add(JSON.readTree(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
                byte[] body = "{\"ok\":true,\"ts\":\"1700000000.000100\"}".getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(status.get(), body.length);
                exchange.getResponseBody().write(body);
            }
        });
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    private ReportAlert alert(String token, String channel, Executor executor) {
        String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        return new ReportAlert(RestClient.builder(), baseUrl, token, channel, executor);
    }

    private static String text(JsonNode body) {
        return body.path("text").asString();
    }

    @Test
    void 공부방_멤버_신고_한_건을_신고_채널로_보낸다() {
        ReportAlert alert = alert(TOKEN, CHANNEL, INLINE);

        alert.send(MEMBER_REPORT);

        assertThat(alert.isEnabled()).isTrue();
        assertThat(authorizations).containsExactly("Bearer " + TOKEN);
        assertThat(bodies).singleElement().satisfies(body -> {
            assertThat(body.path("channel").asString()).isEqualTo(CHANNEL);
            assertThat(text(body))
                    .contains("공부방 멤버 신고 (ROOM_MEMBER)")
                    .contains("신고한 사람: userId 12")
                    .contains("신고된 사람: userId 34")
                    .contains("그룹: groupId 7")
                    // 사용자 입력은 멘션이 되지 않게 대체 글에서 이스케이프된다
                    .contains("사유: 카메라를 켜고 &lt;!channel&gt; 했어요")
                    .contains("시각: 2026-11-03 14:30:05 KST")
                    .doesNotContain("applicationId");
        });
    }

    @Test
    void 신청_신고는_신청_ID를_싣고_사유가_없으면_없음으로_적는다() {
        ReportAlert alert = alert(TOKEN, CHANNEL, INLINE);

        alert.send(APPLICATION_REPORT);

        assertThat(bodies)
                .singleElement()
                .satisfies(body -> assertThat(text(body))
                        .contains("신청 신고 (APPLICATION)")
                        .contains("신고된 사람: userId 56")
                        .contains("신청: applicationId 55")
                        .contains("사유: (없음)"));
    }

    @Test
    void 채널이나_토큰이_비면_아무것도_보내지_않는다() {
        for (ReportAlert alert : List.of(
                alert(TOKEN, "", INLINE),
                alert(TOKEN, "  ", INLINE),
                alert("", CHANNEL, INLINE),
                alert(null, null, INLINE))) {
            assertThat(alert.isEnabled()).isFalse();
            alert.send(MEMBER_REPORT);
        }

        assertThat(bodies).isEmpty();
    }

    @Test
    void 요청_스레드가_아니라_실행기에서_보낸다() {
        List<Runnable> queued = new ArrayList<>();
        ReportAlert alert = alert(TOKEN, CHANNEL, queued::add);

        alert.send(MEMBER_REPORT);

        assertThat(bodies).as("실행기가 돌기 전에는 보내지 않는다").isEmpty();
        assertThat(queued).hasSize(1);
        queued.getFirst().run();
        assertThat(bodies).hasSize(1);
    }

    @Test
    void 트랜잭션_안에서는_커밋된_뒤에_보내고_롤백되면_보내지_않는다() {
        ReportAlert alert = alert(TOKEN, CHANNEL, INLINE);

        TransactionSynchronizationManager.initSynchronization();
        alert.send(MEMBER_REPORT);
        assertThat(bodies).as("커밋 전").isEmpty();
        // 롤백 — afterCommit이 불리지 않고 동기화가 정리된다
        TransactionSynchronizationManager.clearSynchronization();
        assertThat(bodies).isEmpty();

        TransactionSynchronizationManager.initSynchronization();
        alert.send(APPLICATION_REPORT);
        List<TransactionSynchronization> registered = TransactionSynchronizationManager.getSynchronizations();
        TransactionSynchronizationManager.clearSynchronization();
        registered.forEach(TransactionSynchronization::afterCommit);

        assertThat(bodies)
                .singleElement()
                .satisfies(body -> assertThat(text(body)).contains("신청 신고"));
    }

    @Test
    void 슬랙이_실패해도_예외를_던지지_않는다() {
        status.set(500);
        ReportAlert alert = alert(TOKEN, CHANNEL, INLINE);

        assertThatCode(() -> alert.send(MEMBER_REPORT)).doesNotThrowAnyException();
        assertThat(bodies).hasSize(1);
    }
}
