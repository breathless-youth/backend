package project.study.room.turn;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import project.study.room.dto.RoomJoinResponse.IceServer;

class CloudflareTurnCredentialProviderTest {

    private static final String BASE = "https://cf.test";
    private static final String URL = BASE + "/v1/turn/keys/key-1/credentials/generate-ice-servers";
    private static final String ARRAY_BODY = """
            {"iceServers":[{"urls":["stun:stun.cloudflare.com:3478","turn:turn.cloudflare.com:3478?transport=udp"],
                            "username":"u1","credential":"c1"}]}
            """;
    private static final String OBJECT_BODY = """
            {"iceServers":{"urls":["turn:turn.cloudflare.com:3478?transport=tcp"],"username":"u2","credential":"c2"}}
            """;

    /** 테스트에서 시간을 밀 수 있는 시계 */
    private static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-09-03T00:00:00Z");

        void advance(Duration d) {
            now = now.plus(d);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    private final MutableClock clock = new MutableClock();
    private final RestClient.Builder builder = RestClient.builder();
    private final MockRestServiceServer server =
            MockRestServiceServer.bindTo(builder).build();

    /** 운영에선 CloudflareTurnConfig가 base URL·Bearer 헤더를 고정한다 — 여기선 mock 서버에 바인딩된 클라이언트로 재현 */
    private CloudflareTurnCredentialProvider provider(String keyId, String token) {
        RestClient client = builder.baseUrl(BASE)
                .defaultHeader("Authorization", "Bearer " + token)
                .build();
        return new CloudflareTurnCredentialProvider(client, keyId, token, 3600, clock);
    }

    @Test
    void 설정이_비어있으면_비활성이고_외부호출_없이_빈목록() {
        CloudflareTurnCredentialProvider p = provider("", "");

        assertThat(p.isEnabled()).isFalse();
        assertThat(p.iceServers()).isEmpty();
        server.verify(); // 요청 0건
    }

    @Test
    void 배열형_응답을_IceServer_목록으로_매핑하고_Bearer_토큰과_ttl을_보낸다() {
        server.expect(once(), requestTo(URL))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer tok"))
                .andExpect(jsonPath("$.ttl").value(3600))
                .andRespond(withSuccess(ARRAY_BODY, MediaType.APPLICATION_JSON));

        List<IceServer> servers = provider("key-1", "tok").iceServers();

        assertThat(servers).hasSize(1);
        assertThat(servers.getFirst().urls())
                .containsExactly("stun:stun.cloudflare.com:3478", "turn:turn.cloudflare.com:3478?transport=udp");
        assertThat(servers.getFirst().username()).isEqualTo("u1");
        assertThat(servers.getFirst().credential()).isEqualTo("c1");
        server.verify();
    }

    @Test
    void 단일객체형_응답도_받는다() {
        server.expect(once(), requestTo(URL)).andRespond(withSuccess(OBJECT_BODY, MediaType.APPLICATION_JSON));

        List<IceServer> servers = provider("key-1", "tok").iceServers();

        assertThat(servers).singleElement().satisfies(s -> {
            assertThat(s.urls()).containsExactly("turn:turn.cloudflare.com:3478?transport=tcp");
            assertThat(s.username()).isEqualTo("u2");
        });
    }

    @Test
    void TTL_절반_전에는_캐시를_쓰고_지나면_재발급한다() {
        server.expect(once(), requestTo(URL)).andRespond(withSuccess(ARRAY_BODY, MediaType.APPLICATION_JSON));
        server.expect(once(), requestTo(URL)).andRespond(withSuccess(OBJECT_BODY, MediaType.APPLICATION_JSON));
        CloudflareTurnCredentialProvider p = provider("key-1", "tok");

        assertThat(p.iceServers().getFirst().username()).isEqualTo("u1");
        clock.advance(Duration.ofMinutes(29)); // ttl 3600s → 절반(1800s) 이전
        assertThat(p.iceServers().getFirst().username()).isEqualTo("u1"); // 캐시 (2번째 요청 안 나감)
        clock.advance(Duration.ofMinutes(2)); // 31분 경과 → 갱신
        assertThat(p.iceServers().getFirst().username()).isEqualTo("u2");
        server.verify();
    }

    @Test
    void 발급_실패시_캐시가_없으면_빈목록_예외없음() {
        server.expect(once(), requestTo(URL)).andRespond(withServerError());

        assertThat(provider("key-1", "tok").iceServers()).isEmpty();
    }

    @Test
    void 갱신_실패시_만료_전_캐시를_계속_내려주고_만료_후엔_빈목록() {
        server.expect(once(), requestTo(URL)).andRespond(withSuccess(ARRAY_BODY, MediaType.APPLICATION_JSON));
        server.expect(once(), requestTo(URL)).andRespond(withServerError());
        server.expect(once(), requestTo(URL)).andRespond(withServerError());
        CloudflareTurnCredentialProvider p = provider("key-1", "tok");

        assertThat(p.iceServers()).hasSize(1);
        clock.advance(Duration.ofMinutes(31)); // 갱신 시점, 실패 → 아직 만료(60분) 전이라 캐시 유지
        assertThat(p.iceServers()).hasSize(1);
        clock.advance(Duration.ofMinutes(30)); // 61분 경과 → 만료, 실패 → 빈 목록
        assertThat(p.iceServers()).isEmpty();
        server.verify();
    }

    @Test
    void 발급_실패_후_60초_동안은_재호출하지_않고_그_뒤_재시도한다() {
        server.expect(once(), requestTo(URL)).andRespond(withServerError());
        server.expect(once(), requestTo(URL)).andRespond(withSuccess(ARRAY_BODY, MediaType.APPLICATION_JSON));
        CloudflareTurnCredentialProvider p = provider("key-1", "tok");

        assertThat(p.iceServers()).isEmpty();
        clock.advance(Duration.ofSeconds(30));
        assertThat(p.iceServers()).isEmpty(); // 백오프 중 — 2번째 요청 안 나감
        clock.advance(Duration.ofSeconds(31)); // 61초 경과 → 재시도 성공
        assertThat(p.iceServers()).hasSize(1);
        server.verify();
    }

    @Test
    void 만료_직전_갱신_실패_후엔_백오프_중이라도_만료_시각부터_빈목록이다() {
        server.expect(once(), requestTo(URL)).andRespond(withSuccess(ARRAY_BODY, MediaType.APPLICATION_JSON));
        server.expect(once(), requestTo(URL)).andRespond(withServerError());
        server.expect(once(), requestTo(URL)).andRespond(withServerError());
        CloudflareTurnCredentialProvider p = provider("key-1", "tok");

        assertThat(p.iceServers()).hasSize(1); // 발급, 만료 = +3600s
        clock.advance(Duration.ofSeconds(3580)); // 만료 20초 전 갱신 실패 → 백오프 60s가 만료(20s 뒤)를 넘지 않게 캡
        assertThat(p.iceServers()).hasSize(1);
        clock.advance(Duration.ofSeconds(25)); // 만료 5초 경과 — 백오프 중이었어도 만료된 자격은 못 준다 → 재시도(실패) → 빈 목록
        assertThat(p.iceServers()).isEmpty();
        server.verify();
    }

    @Test
    void urls가_없는_항목은_버린다() {
        server.expect(once(), requestTo(URL))
                .andRespond(withSuccess("{\"iceServers\":[{\"username\":\"x\"}]}", MediaType.APPLICATION_JSON));

        assertThat(provider("key-1", "tok").iceServers()).isEmpty();
    }
}
