package project.study.room.support;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import project.study.room.dto.RoomMember;
import project.study.room.repository.RoomRepository;
import project.study.room.service.AutoLeave;
import project.study.room.service.RoomCleanupService;
import project.study.room.service.RoomService;
import project.study.room.service.RoomService.JoinResult;
import project.study.room.service.RoomStateService;

/** 룸 서비스 통합 테스트 공통 — 실제 유저·방을 만들고 옛 인메모리 테스트와 같은 어휘(join/confirm/cleanupAfter)를 제공한다. */
public abstract class RoomTestBase {

    protected static final String TASK = "task-test";
    private static final SecureRandom RANDOM = new SecureRandom();

    @Autowired
    protected RoomService roomService;

    @Autowired
    protected RoomStateService roomState;

    @Autowired
    protected RoomCleanupService roomCleanup;

    @Autowired
    protected RoomRepository rooms;

    @Autowired
    private JdbcClient jdbc;

    protected RoomProbe probe;
    protected long owner;

    @BeforeEach
    void setUpBase() {
        probe = new RoomProbe(jdbc);
        owner = probe.insertUser();
    }

    protected long user() {
        return probe.insertUser();
    }

    protected String createRoom() {
        return roomService.create(owner).inviteCode();
    }

    // 프로필 값이 중요하지 않은 테스트용 기본 join — 닉네임·목표는 join 시점에 호출자가 전달한다
    protected JoinResult join(long userId, String code) {
        return roomService.join(userId, code, "포메" + userId, null, null);
    }

    protected List<RoomMember> confirm(long roomId, long userId, String sessionId) {
        return roomService.confirmStomp(roomId, userId, sessionId, Instant.now(), TASK);
    }

    protected List<RoomMember> confirmOpenedAt(long roomId, long userId, String sessionId, Instant openedAt) {
        return roomService.confirmStomp(roomId, userId, sessionId, openedAt, TASK);
    }

    /** 지정한 방들의 AutoLeave만 돌려준다 — 트랜잭션 없는 다른 테스트가 커밋한 방의 만료가 섞이지 않게. 방을 안 주면 전부. */
    protected List<AutoLeave> cleanupAfter(long seconds, long... roomIds) {
        Set<Long> scope = Arrays.stream(roomIds).boxed().collect(Collectors.toSet());
        return roomCleanup.cleanupExpired(Instant.now().plusSeconds(seconds)).stream()
                .filter(al -> scope.isEmpty() || scope.contains(al.roomId()))
                .toList();
    }

    /** 어느 방도 쓰지 않는 코드 — 랜덤 발급이라 "없는 코드"를 고정할 수 없어서 조회로 고른다. */
    protected String unusedCode() {
        for (int i = 0; i < 1000; i++) {
            String code = String.format("%04d", RANDOM.nextInt(10000));
            if (rooms.findLatestByCode(code).isEmpty()) return code;
        }
        throw new IllegalStateException("빈 코드를 못 찾음");
    }
}
