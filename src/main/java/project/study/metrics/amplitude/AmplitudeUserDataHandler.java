package project.study.metrics.amplitude;

import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import project.study.user.account.UserDataHandler;

/**
 * 계정 병합·탈퇴 시 Amplitude 적재본 처리. 개인정보처리방침에 무기한 보관을 쓸 수 없어 탈퇴하면 함께 지운다. 병합에서도 옮기는 쪽 것을
 * 지운다. 리포트·실험 집계는 users에 있는 회원만 읽어 옮기는 쪽 행은 병합 뒤 아무 데도 쓰이지 않고, 남겨 두면 나중에 탈퇴해도 지울 길이 없다.
 */
@Component
@RequiredArgsConstructor
public class AmplitudeUserDataHandler implements UserDataHandler {

    private final JdbcClient jdbc;

    @Override
    public Set<String> columns() {
        return Set.of("amplitude_daily_event.user_id", "amplitude_user_attribute.user_id");
    }

    @Override
    public int order() {
        return 500;
    }

    @Override
    public void merge(long sourceUserId, long targetUserId) {
        erase(sourceUserId);
    }

    @Override
    public void erase(long userId) {
        jdbc.sql("DELETE FROM amplitude_daily_event WHERE user_id = :id")
                .param("id", userId)
                .update();
        jdbc.sql("DELETE FROM amplitude_user_attribute WHERE user_id = :id")
                .param("id", userId)
                .update();
    }
}
