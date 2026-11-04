package project.study.group.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;
import project.study.group.dto.GroupRoomJoinResponse;
import project.study.room.service.RoomService;
import project.study.room.service.RoomService.GroupRoomClosedException;
import project.study.room.service.RoomService.GroupRoomJoin;
import project.study.room.websocket.RoomMessenger;
import project.study.user.dto.ProfileResponse;
import project.study.user.service.UserService;

/**
 * 찾은 그룹 공부방이 잠그기 전에 닫힌 경합 (BY-1124) — 같은 트랜잭션에서 다시 열면 이미 잠근 지금 방을 쥔 채 기다려 교착이 날 수 있어, 트랜잭션을
 * 통째로 새로 열어 다시 하는지 본다. 실제 DB에서 그 순간을 재현할 고리가 없어 트랜잭션 경계만 흉내 낸다.
 */
class GroupRoomServiceRetryTest {

    private static final long USER = 7L;
    private static final long GROUP = 10L;

    private final RoomService roomService = mock(RoomService.class);
    private final UserService users = mock(UserService.class);
    private final TransactionTemplate tx = mock(TransactionTemplate.class);
    private final GroupRoomService service = new GroupRoomService(
            mock(GroupAccess.class), roomService, users, mock(RoomMessenger.class), tx, Clock.systemUTC());

    @BeforeEach
    void setUp() {
        // 콜백을 바로 실행한다 — 시도마다 execute가 한 번씩 불리는지(= 트랜잭션을 새로 여는지)만 센다
        when(tx.execute(any()))
                .thenAnswer(inv ->
                        ((TransactionCallback<?>) inv.getArgument(0)).doInTransaction(mock(TransactionStatus.class)));
        when(users.getProfile(USER)).thenReturn(new ProfileResponse("포메", null, null, "포", 0, null));
    }

    @Test
    void 닫힘_경합이면_트랜잭션을_새로_열어_다시_한다() {
        GroupRoomJoinResponse seated = new GroupRoomJoinResponse(3L, false, null, null, null, List.of(), 60);
        when(roomService.joinGroupRoom(USER, GROUP, "포메", null, null))
                .thenThrow(new GroupRoomClosedException(GROUP))
                .thenReturn(new GroupRoomJoin(seated, null));

        assertThat(service.join(USER, GROUP)).isEqualTo(seated);
        verify(tx, times(2)).execute(any());
    }

    @Test
    void 세_번_모두_닫히면_더_돌지_않고_던진다() {
        when(roomService.joinGroupRoom(USER, GROUP, "포메", null, null)).thenThrow(new GroupRoomClosedException(GROUP));

        assertThatThrownBy(() -> service.join(USER, GROUP)).isInstanceOf(GroupRoomClosedException.class);
        verify(tx, times(3)).execute(any());
    }
}
