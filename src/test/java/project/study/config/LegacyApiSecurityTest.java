package project.study.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import project.study.TestcontainersConfiguration;

/**
 * 구 앱(v1.2.x) 병행 기간의 인가 규칙 (ADR-0020). 구 앱은 토큰도 {@code API-Version} 헤더도 못 보내므로
 * 구 앱이 쓰는 경로만, 헤더가 없거나 1일 때만 인증 없이 통과한다. 그 외는 ADR-0019 그대로 401이다.
 *
 * <p>통과 판정은 "401이 아님"이다 — 본문 없는 POST는 400, 없는 유저는 404가 나지만 그건 인가를 지난 뒤의 응답이다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class LegacyApiSecurityTest {

    private static final String API_VERSION = "API-Version";

    @Autowired
    private MockMvcTester mvc;

    /** 구 앱이 호출하는 보호 경로 14개 — v1.2.1 계약 그대로. */
    static Stream<Arguments> legacyProtectedCalls() {
        return Stream.of(
                Arguments.of(HttpMethod.GET, "/api/users/1/profile"),
                Arguments.of(HttpMethod.PATCH, "/api/users/1/profile"),
                Arguments.of(HttpMethod.POST, "/api/rooms"),
                Arguments.of(HttpMethod.POST, "/api/rooms/join"),
                Arguments.of(HttpMethod.POST, "/api/rooms/1/leave"),
                Arguments.of(HttpMethod.POST, "/api/study-sessions"),
                Arguments.of(HttpMethod.GET, "/api/study-sessions/1"),
                Arguments.of(HttpMethod.PUT, "/api/study-sessions/active"),
                Arguments.of(HttpMethod.GET, "/api/study-sessions/active"),
                Arguments.of(HttpMethod.POST, "/api/study-sessions/recovery"),
                Arguments.of(HttpMethod.GET, "/api/stats"),
                Arguments.of(HttpMethod.GET, "/api/stats/streak"),
                Arguments.of(HttpMethod.GET, "/api/stats/period"),
                Arguments.of(HttpMethod.POST, "/api/rtc-stats"));
    }

    @ParameterizedTest(name = "{0} {1}")
    @MethodSource("legacyProtectedCalls")
    void 구_앱_경로는_헤더와_토큰_없이_인가를_통과한다(HttpMethod method, String path) {
        assertThat(call(method, path, null).getResponse().getStatus()).isNotEqualTo(HttpStatus.UNAUTHORIZED.value());
    }

    @ParameterizedTest(name = "{0} {1}")
    @MethodSource("legacyProtectedCalls")
    void 구_앱_경로도_API_Version_1을_명시하면_인가를_통과한다(HttpMethod method, String path) {
        assertThat(call(method, path, "1").getResponse().getStatus()).isNotEqualTo(HttpStatus.UNAUTHORIZED.value());
    }

    @ParameterizedTest(name = "{0} {1}")
    @MethodSource("legacyProtectedCalls")
    void 같은_경로라도_API_Version_2면_토큰_없이는_401이다(HttpMethod method, String path) {
        assertThat(call(method, path, "2")).hasStatus(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void 토큰_계약_전용_경로는_헤더가_없어도_401이다() {
        assertThat(mvc.get().uri("/api/users/me/profile")).hasStatus(HttpStatus.UNAUTHORIZED);
        assertThat(mvc.get().uri("/api/stats/study-days")).hasStatus(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void 구_앱_프로필_경로는_숫자_userId만_열린다() {
        // /{userId}/profile 패턴을 문자열까지 열면 /me/profile이 헤더 없이 뚫린다
        assertThat(mvc.get().uri("/api/users/abc/profile")).hasStatus(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void api_밖의_보호_경로는_헤더가_없어도_401이다() {
        assertThat(mvc.get().uri("/actuator/metrics")).hasStatus(HttpStatus.UNAUTHORIZED);
    }

    private MvcTestResult call(HttpMethod method, String path, String apiVersion) {
        var builder = mvc.method(method)
                .uri(path)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}");
        if (apiVersion != null) {
            builder = builder.header(API_VERSION, apiVersion);
        }
        return builder.exchange();
    }
}
