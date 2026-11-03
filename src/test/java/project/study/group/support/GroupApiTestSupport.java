package project.study.group.support;

import static org.assertj.core.api.Assertions.assertThat;
import static project.study.support.AuthTestSupport.asUser;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MockMvcTester.MockMvcRequestBuilder;
import project.study.config.ApiVersionConfig;

/**
 * 그룹 API 테스트가 함께 쓰는 요청 빌더·단언 (BY-1124). 그룹 경로는 모두 새 경로라 {@code API-Version: 1}이고, {@code asUser}의 기본
 * 헤더(2)를 덮어야 한다 — 기능별로 나눈 테스트 클래스마다 같은 빌더를 다시 쓰지 않으려고 모았다.
 */
public final class GroupApiTestSupport {

    private GroupApiTestSupport() {}

    public static MockMvcRequestBuilder v1Get(MockMvcTester mvc, String uri, long userId) {
        return asV1(mvc.get().uri(uri), userId);
    }

    public static MockMvcRequestBuilder v1Post(MockMvcTester mvc, String uri, long userId) {
        return asV1(mvc.post().uri(uri), userId);
    }

    public static MockMvcRequestBuilder v1Put(MockMvcTester mvc, String uri, long userId) {
        return asV1(mvc.put().uri(uri), userId);
    }

    public static MockMvcRequestBuilder v1Patch(MockMvcTester mvc, String uri, long userId) {
        return asV1(mvc.patch().uri(uri), userId);
    }

    public static MockMvcRequestBuilder v1Delete(MockMvcTester mvc, String uri, long userId) {
        return asV1(mvc.delete().uri(uri), userId);
    }

    /** JSON 본문을 싣는다. */
    public static MockMvcRequestBuilder json(MockMvcRequestBuilder builder, String body) {
        return builder.contentType(MediaType.APPLICATION_JSON).content(body);
    }

    /** 인증·검증을 통과했지만 로직이 아직 없는 요청은 501 {@code NOT_IMPLEMENTED}다. */
    public static void assertNotImplemented(MockMvcRequestBuilder request) {
        assertErrorCode(request, HttpStatus.NOT_IMPLEMENTED, "NOT_IMPLEMENTED");
    }

    /** 요청 검증 실패는 400 {@code VALIDATION_FAILED}다. */
    public static void assertValidationFailed(MockMvcRequestBuilder request) {
        assertErrorCode(request, HttpStatus.BAD_REQUEST, "VALIDATION_FAILED");
    }

    /** 상태 코드와 본문 {@code code}가 모두 맞아야 한다 — FE는 code로만 이유를 가른다. */
    public static void assertErrorCode(MockMvcRequestBuilder request, HttpStatus status, String code) {
        assertThat(request)
                .hasStatus(status)
                .bodyJson()
                .hasPathSatisfying("$.code", v -> assertThat(v).isEqualTo(code));
    }

    /** 요청의 상태 코드만 돌려준다 — {@link #race}의 작업으로 쓴다. */
    public static int status(MockMvcRequestBuilder request) {
        return request.exchange().getResponse().getStatus();
    }

    /** 작업을 스레드마다 하나씩 한꺼번에 출발시켜 돌려받은 값을 모은다 — 동시 요청이 한도·자리를 넘는지 보는 테스트용이다. */
    public static <T> List<T> race(List<Callable<T>> tasks) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(tasks.size());
        CountDownLatch ready = new CountDownLatch(tasks.size());
        CountDownLatch go = new CountDownLatch(1);
        try {
            List<Future<T>> futures = new ArrayList<>();
            for (Callable<T> task : tasks) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    return task.call();
                }));
            }
            ready.await();
            go.countDown();
            List<T> results = new ArrayList<>();
            for (Future<T> future : futures) {
                results.add(future.get());
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }

    private static MockMvcRequestBuilder asV1(MockMvcRequestBuilder builder, long userId) {
        return builder.header(ApiVersionConfig.HEADER, "1").with(asUser(userId));
    }
}
