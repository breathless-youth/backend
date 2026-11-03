package project.study.group.config;

import java.util.concurrent.Executors;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import project.study.group.push.GroupNotifications;
import project.study.push.service.PushSender;

/**
 * 그룹 푸시 발송기를 만든다 (BY-1124). FCM 호출은 가상 스레드 실행기로 넘겨 요청 스레드를 붙잡지 않는다.
 *
 * <p>실행기를 {@code Executor} 타입 빈으로 노출하지 않고 {@link GroupNotifications} 안에서만 쥔다 — 그런 빈이 생기면 Boot의 기본
 * 실행기 자동 구성이 물러나 다른 곳(MVC 비동기 처리 등)이 쓰는 실행기가 바뀐다({@code MetricsJobRunner}와 같은 이유). 가상 스레드는
 * 데몬이라 종료를 막지 않고, 종료 순간에 보내던 푸시는 유실될 수 있다 — 푸시는 최선 노력이다.
 */
@Configuration
public class GroupPushConfig {

    @Bean
    GroupNotifications groupNotifications(PushSender pushSender) {
        return new GroupNotifications(pushSender, Executors.newVirtualThreadPerTaskExecutor());
    }
}
