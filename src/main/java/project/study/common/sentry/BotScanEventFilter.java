package project.study.common.sentry;

import io.sentry.Hint;
import io.sentry.SentryEvent;
import io.sentry.SentryOptions;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * 외부 스캐너가 두드리는 없는 경로의 404를 Sentry로 보내지 않는다 (ADR-0024).
 *
 * <p>ADR-0011로 4xx도 전부 수집하는데, {@code livewire/update}·{@code wp-content/...} 같은 봇 요청이
 * 월 무료 한도를 다 써서 실제 장애까지 못 받게 됐다. {@code /api/} 아래의 없는 경로는 앱이 잘못된 URL을
 * 부르는 버그일 수 있어 계속 보낸다 — 우리 앱은 {@code /api/} 밖을 부르지 않는다.
 *
 * <p>Sentry 자동 설정이 이 빈을 {@link SentryOptions#setBeforeSend}에 등록한다. DSN이 없으면 SDK가
 * no-op이라 호출되지 않는다.
 */
@Component
public class BotScanEventFilter implements SentryOptions.BeforeSendCallback {

    private static final String API_ROOT = "api";

    @Override
    public SentryEvent execute(SentryEvent event, Hint hint) {
        // getThrowable()은 리졸버가 씌운 ExceptionMechanismException을 벗겨 원본을 돌려준다
        if (event.getThrowable() instanceof NoResourceFoundException e && isOutsideApi(e.getResourcePath())) {
            return null;
        }
        return event;
    }

    private static boolean isOutsideApi(String resourcePath) {
        // 선행 슬래시 유무는 리소스 핸들러 매핑에 따라 달라질 수 있어 둘 다 받는다.
        // Spring이 끝 슬래시를 떼므로 /api/ 요청은 "api"로 들어온다
        String path = resourcePath.startsWith("/") ? resourcePath.substring(1) : resourcePath;
        return !(path.equals(API_ROOT) || path.startsWith(API_ROOT + "/"));
    }
}
