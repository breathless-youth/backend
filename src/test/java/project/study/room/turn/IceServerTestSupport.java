package project.study.room.turn;

import java.util.List;
import project.study.room.dto.RoomJoinResponse.IceServer;

/** 테스트용 ICE 팩토리: TURN 없음(빈 목록) / 고정 목록 */
public final class IceServerTestSupport {

    private IceServerTestSupport() {}

    public static IceServerFactory none() {
        return new IceServerFactory("test-secret", 86400, List.of(), false, CloudflareTurnTestSupport.disabled());
    }

    public static IceServerFactory fixed(int ttlSeconds, List<IceServer> servers) {
        return new IceServerFactory("test-secret", ttlSeconds, List.of(), false, CloudflareTurnTestSupport.disabled()) {
            @Override
            public List<IceServer> forUser(Long userId) {
                return servers;
            }
        };
    }
}
