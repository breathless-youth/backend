package project.study.common.logging;

import java.util.Map;
import org.slf4j.MDC;
import org.springframework.core.task.TaskDecorator;

/**
 * 제출 스레드의 MDC를 실행 스레드로 복사한다 — {@code @Async}로 넘어간 작업의 로그에도 userId가 붙게.
 *
 * <p>실행이 끝나면 실행 스레드의 MDC를 비운다. 풀 스레드는 재사용되므로 남겨두면 다음 작업에
 * 이전 유저의 컨텍스트가 묻어간다.
 */
public class MdcTaskDecorator implements TaskDecorator {

    @Override
    public Runnable decorate(Runnable runnable) {
        Map<String, String> context = MDC.getCopyOfContextMap();
        return () -> {
            if (context != null) {
                MDC.setContextMap(context);
            }
            try {
                runnable.run();
            } finally {
                MDC.clear();
            }
        };
    }
}
