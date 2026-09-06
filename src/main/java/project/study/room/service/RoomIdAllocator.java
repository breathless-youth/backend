package project.study.room.service;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 방 ID 발급 — DB 시퀀스 {@code room_id_seq} (BY-626).
 *
 * <p>메모리 카운터는 태스크가 재시작하면 0부터 다시 세므로, 스냅샷으로 복원한 옛 방과 새로 만든 방이 같은
 * {@code /topic/room/{id}}를 쓰게 된다. 시퀀스는 태스크가 몇 번 바뀌어도 단조 증가한다. 호출은
 * {@link RoomService} 글로벌 락 밖(컨트롤러)에서 한다 — 락 안에서 I/O를 하지 않는 규칙.
 */
@Component
@RequiredArgsConstructor
public class RoomIdAllocator {

    private final JdbcTemplate jdbcTemplate;

    public long next() {
        Long id = jdbcTemplate.queryForObject("select nextval('room_id_seq')", Long.class);
        if (id == null) {
            throw new IllegalStateException("room_id_seq가 값을 돌려주지 않았다");
        }
        return id;
    }
}
