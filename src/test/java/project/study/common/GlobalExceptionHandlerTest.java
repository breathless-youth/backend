package project.study.common;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * 전역 예외 핸들러가 내리는 상태코드와 응답 포맷만 검증한다.
 * Sentry로 이벤트가 실제로 전송되는지는 자동 검증하지 않는다 — Sentry SDK 진입점이 static이라
 * 래퍼 없이는 호출을 관측할 수 없고, 래퍼를 두지 않기로 결정했다
 * (설계: docs/superpowers/specs/2026-08-06-sentry-4xx-collection-design.md).
 * 다만 Sentry 리졸버가 이 핸들러보다 먼저 실행되는지(=캡처될 기회가 있는지)는
 * {@link SentryExceptionResolverOrderTest}에서 검증한다.
 *
 * <p>DB가 필요 없는 검증이라 standalone으로 띄워 Testcontainers 기동 비용을 피한다.
 */
class GlobalExceptionHandlerTest {

    private final MockMvcTester mvc = MockMvcTester.of(
            List.of(new ThrowingController()),
            builder -> builder.setControllerAdvice(new GlobalExceptionHandler()).build());

    @Test
    void 예상하지_못한_예외는_500과_공통_포맷으로_응답한다() {
        assertThat(mvc.get().uri("/test/unexpected").exchange())
                .hasStatus(HttpStatus.INTERNAL_SERVER_ERROR)
                .bodyJson()
                .hasPathSatisfying("$.message", message -> assertThat(message).isEqualTo("서버 오류가 발생했습니다"))
                .hasPathSatisfying("$.code", code -> assertThat(code).isEqualTo("INTERNAL_ERROR"));
    }

    @Test
    void 내부_예외_메시지는_응답에_노출하지_않는다() {
        assertThat(mvc.get().uri("/test/unexpected").exchange())
                .bodyJson()
                .hasPathSatisfying(
                        "$.message", message -> assertThat(message).asString().doesNotContain("db connection refused"));
    }

    @Test
    void NotFoundException은_404로_응답한다() {
        assertThat(mvc.get().uri("/test/not-found").exchange()).hasStatus(HttpStatus.NOT_FOUND);
    }

    @Test
    void 코드를_지정하지_않은_예외는_상태별_기본_코드로_응답한다() {
        assertThat(mvc.get().uri("/test/not-found").exchange())
                .bodyJson()
                .hasPathSatisfying("$.code", code -> assertThat(code).isEqualTo("NOT_FOUND"));
    }

    /** 상태코드만으로 갈라지지 않는 404를 클라이언트가 구분할 수 있어야 한다 (BY-436) */
    @Test
    void 예외가_지정한_에러_코드를_그대로_내려준다() {
        assertThat(mvc.get().uri("/test/room-closed").exchange())
                .hasStatus(HttpStatus.NOT_FOUND)
                .bodyJson()
                .hasPathSatisfying("$.code", code -> assertThat(code).isEqualTo("ROOM_CLOSED"))
                .hasPathSatisfying("$.message", message -> assertThat(message).isEqualTo("방이 종료되었어요"));
    }

    @Test
    void BadRequestException은_400으로_응답한다() {
        assertThat(mvc.get().uri("/test/bad-request").exchange()).hasStatus(HttpStatus.BAD_REQUEST);
    }

    @Test
    void ConflictException은_409로_응답한다() {
        assertThat(mvc.get().uri("/test/conflict").exchange()).hasStatus(HttpStatus.CONFLICT);
    }

    /**
     * 회귀 방지 테스트. @ExceptionHandler(Exception.class)는 Spring MVC 표준 예외를 담당하는
     * DefaultHandlerExceptionResolver보다 먼저 실행되므로, ErrorResponse 분기가 없으면
     * 405가 500으로 바뀐다.
     */
    @Test
    void 지원하지_않는_HTTP_메서드는_405를_유지한다() {
        assertThat(mvc.get().uri("/test/post-only").exchange()).hasStatus(HttpStatus.METHOD_NOT_ALLOWED);
    }

    /**
     * ResponseStatusException도 ErrorResponse를 구현하므로 위 405 분기에 함께 걸린다.
     * 이 분기는 응답 메시지 선택만 담당한다 — 4xx는 "요청을 처리할 수 없습니다",
     * 5xx는 내부 사정을 감추는 "서버 오류가 발생했습니다"로 갈라야 하므로 상태코드를 다시 본다.
     */
    @Test
    void 표준_예외라도_5xx면_서버_오류_메시지로_응답한다() {
        assertThat(mvc.get().uri("/test/service-unavailable").exchange())
                .hasStatus(HttpStatus.SERVICE_UNAVAILABLE)
                .bodyJson()
                .hasPathSatisfying("$.message", message -> assertThat(message).isEqualTo("서버 오류가 발생했습니다"));
    }

    @RestController
    static class ThrowingController {

        @GetMapping("/test/unexpected")
        void unexpected() {
            throw new IllegalStateException("db connection refused");
        }

        @GetMapping("/test/not-found")
        void notFound() {
            throw new NotFoundException("없는 사용자입니다");
        }

        @GetMapping("/test/room-closed")
        void roomClosed() {
            throw new NotFoundException(ErrorCode.ROOM_CLOSED, "방이 종료되었어요");
        }

        @GetMapping("/test/bad-request")
        void badRequest() {
            throw new BadRequestException("잘못된 요청입니다");
        }

        @GetMapping("/test/conflict")
        void conflict() {
            throw new ConflictException("이미 존재합니다");
        }

        @GetMapping("/test/service-unavailable")
        void serviceUnavailable() {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "일시적으로 사용할 수 없습니다");
        }

        @PostMapping("/test/post-only")
        void postOnly() {}
    }
}
