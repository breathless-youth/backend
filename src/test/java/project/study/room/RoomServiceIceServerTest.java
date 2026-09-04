package project.study.room;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import project.study.room.dto.RoomJoinResponse;
import project.study.room.dto.RoomJoinResponse.IceServer;
import project.study.room.service.RoomService;
import project.study.room.turn.IceServerTestSupport;

class RoomServiceIceServerTest {

    @Test
    void 입장_응답의_iceServers와_ttl은_IceServerFactory_결과를_그대로_담는다() {
        IceServer cf = new IceServer(List.of("turn:turn.cloudflare.com:3478?transport=udp"), "u", "c");
        RoomService svc = new RoomService(IceServerTestSupport.fixed(1234, List.of(cf)), event -> {});
        String code = svc.create(1L).inviteCode();

        RoomJoinResponse res = svc.join(2L, code, "닉", "목표", "공부").response();

        assertThat(res.iceServers()).containsExactly(cf);
        assertThat(res.iceTtlSeconds()).isEqualTo(1234);
    }
}
