package project.study.room.service;

import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import project.study.user.account.UserDataHandler;

/** 계정 병합·탈퇴 시 방 처리. 만든 방은 탈퇴해도 남기고 created_by만 비우며, 참가 이력은 지운다. */
@Component
@RequiredArgsConstructor
public class RoomUserDataHandler implements UserDataHandler {

    private final JdbcClient jdbc;

    @Override
    public Set<String> columns() {
        return Set.of("rooms.created_by", "room_participations.user_id");
    }

    @Override
    public int order() {
        return 600;
    }

    @Override
    public void merge(long sourceUserId, long targetUserId) {
        // 경쟁으로 아직 방에 있으면 닫아 두어야 남는 쪽의 살아 있는 행과 부분 유니크 인덱스가 부딪히지 않는다
        jdbc.sql("UPDATE room_participations SET left_at = now(), leave_reason = 'SWITCHED_ROOM' "
                        + "WHERE user_id = :source AND left_at IS NULL")
                .param("source", sourceUserId)
                .update();
        jdbc.sql("UPDATE room_participations SET user_id = :target WHERE user_id = :source")
                .param("source", sourceUserId)
                .param("target", targetUserId)
                .update();
        jdbc.sql("UPDATE rooms SET created_by = :target WHERE created_by = :source")
                .param("source", sourceUserId)
                .param("target", targetUserId)
                .update();
    }

    @Override
    public void erase(long userId) {
        jdbc.sql("DELETE FROM room_participations WHERE user_id = :id")
                .param("id", userId)
                .update();
        jdbc.sql("UPDATE rooms SET created_by = NULL WHERE created_by = :id")
                .param("id", userId)
                .update();
    }
}
