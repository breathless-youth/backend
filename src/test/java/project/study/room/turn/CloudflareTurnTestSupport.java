package project.study.room.turn;

import java.time.Clock;
import java.util.List;
import org.springframework.web.client.RestClient;
import project.study.room.dto.RoomJoinResponse.IceServer;

/** 테스트용 제공자 팩토리 — 비활성(설정 없음) 인스턴스 등. */
public final class CloudflareTurnTestSupport {

    private CloudflareTurnTestSupport() {}

    /** 항상 주어진 목록을 돌려주는 제공자 (외부 호출 없음) */
    public static CloudflareTurnCredentialProvider fixed(List<IceServer> servers) {
        return new CloudflareTurnCredentialProvider(
                RestClient.builder().baseUrl("http://cloudflare.invalid").build(), "k", "t", 86400, Clock.systemUTC()) {
            @Override
            public List<IceServer> iceServers() {
                return servers;
            }
        };
    }

    /** key-id/api-token이 비어 비활성인 제공자: 외부 호출 없이 항상 빈 목록. */
    public static CloudflareTurnCredentialProvider disabled() {
        return new CloudflareTurnCredentialProvider(
                RestClient.builder().baseUrl("http://cloudflare.invalid").build(), "", "", 86400, Clock.systemUTC());
    }
}
