package project.study.common.logging;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * HTTP 요청마다 MDC(requestId·userId)를 채우고, 응답 뒤 액세스 로그 한 줄을 남긴다.
 *
 * <p>필터 체인 맨 앞({@link Ordered#HIGHEST_PRECEDENCE})에 둔다 — Security 체인보다 먼저 실행돼
 * 인증 실패 응답까지 같은 requestId로 묶이고, finally에서 MDC를 비우는 것도 이 필터 하나가 책임진다.
 *
 * <p>userId는 쿼리 파라미터({@code ?userId=})에서만 읽는다. POST body의 userId는 필터가 body를
 * 읽지 않는 원칙에 따라 {@link UserIdBodyAdvice}가 채운다. 인증(JWT)을 다시 켤 때는 이 자리를
 * SecurityContext 읽기로 바꾸면 된다.
 *
 * <p><b>부하테스트 때는 이 로거를 WARN으로 내려 액세스 로그를 끈다.</b> k6가 만드는 요청 수만큼
 * CloudWatch 수집량(GB당 과금)이 늘기 때문이다. 이미지 재빌드 없이 태스크 정의 환경변수
 * {@code LOGGING_LEVEL_PROJECT_STUDY_COMMON_LOGGING_REQUESTLOGGINGFILTER=WARN}으로 내릴 수 있다.
 * 평상시 prod에서는 INFO를 유지한다 — 유저별 요청 흐름 추적이 이 로그의 존재 이유다.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestLoggingFilter extends OncePerRequestFilter {

    static final String REQUEST_ID_HEADER = "X-Request-Id";
    private static final String USER_ID_PARAM = "userId";
    private static final String ACTUATOR_PREFIX = "/actuator";

    private static final Logger log = LoggerFactory.getLogger(RequestLoggingFilter.class);

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        // 헬스체크(ALB, 1초 간격)까지 찍으면 액세스 로그가 그것으로 도배된다
        return request.getRequestURI().startsWith(ACTUATOR_PREFIX);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        long startNanos = System.nanoTime();
        try {
            String requestId = resolveRequestId(request);
            MDC.put(LogContext.REQUEST_ID, requestId);
            LogContext.putUserId(request.getParameter(USER_ID_PARAM));
            // 클라이언트가 문의할 때 이 ID를 대면 CloudWatch에서 해당 요청 로그를 바로 찾을 수 있다
            response.setHeader(REQUEST_ID_HEADER, requestId);

            filterChain.doFilter(request, response);
        } finally {
            long durationMs = (System.nanoTime() - startNanos) / 1_000_000;
            log.atInfo()
                    .addKeyValue("method", request.getMethod())
                    .addKeyValue("path", request.getRequestURI())
                    .addKeyValue("status", response.getStatus())
                    .addKeyValue("durationMs", durationMs)
                    .log(
                            "{} {} {} {}ms",
                            request.getMethod(),
                            request.getRequestURI(),
                            response.getStatus(),
                            durationMs);
            // 톰캣 스레드는 재사용된다 — 비우지 않으면 다음 요청에 이전 유저의 ID가 묻어간다
            MDC.clear();
        }
    }

    private static String resolveRequestId(HttpServletRequest request) {
        String fromClient = request.getHeader(REQUEST_ID_HEADER);
        return (fromClient == null || fromClient.isBlank()) ? UUID.randomUUID().toString() : fromClient;
    }
}
