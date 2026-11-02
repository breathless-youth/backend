package project.study.rtcstats.service;

import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import project.study.user.account.UserDataHandler;

/** 계정 병합·탈퇴 시 RTC 통계 처리. 익명 통계라 병합은 그대로 두고, 탈퇴는 본인 행을 지우고 상대로 적힌 자리를 비운다. */
@Component
@RequiredArgsConstructor
public class RtcStatsUserDataHandler implements UserDataHandler {

    private final JdbcClient jdbc;

    @Override
    public Set<String> columns() {
        return Set.of("rtc_connection_stat.user_id", "rtc_connection_stat.peer_user_id");
    }

    @Override
    public int order() {
        return 450;
    }

    @Override
    public void merge(long sourceUserId, long targetUserId) {
        // 연결 품질 통계는 users를 참조하지 않고 계정에 묶이지 않으므로 옮기지 않는다
    }

    @Override
    public void erase(long userId) {
        jdbc.sql("DELETE FROM rtc_connection_stat WHERE user_id = :id")
                .param("id", userId)
                .update();
        jdbc.sql("UPDATE rtc_connection_stat SET peer_user_id = NULL WHERE peer_user_id = :id")
                .param("id", userId)
                .update();
    }
}
