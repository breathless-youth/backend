package project.study.room.turn;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import project.study.room.dto.RoomJoinResponse.IceServer;

class IceServerFactoryTest {

    private static final IceServer CF =
            new IceServer(List.of("turn:turn.cloudflare.com:3478?transport=udp"), "cf-user", "cf-cred");

    @Test
    void coturn_urls도_없고_Cloudflare도_비활성이면_빈목록() {
        IceServerFactory f = new IceServerFactory("s", 60, List.of(), false, CloudflareTurnTestSupport.disabled());

        assertThat(f.forUser(7L)).isEmpty();
        assertThat(f.ttlSeconds()).isEqualTo(60);
    }

    @Test
    void coturn_자격은_만료시각과_userId로_username을_만들고_HMAC_credential을_붙인다() {
        List<String> urls = List.of("stun:161.33.27.117:3478", "turn:161.33.27.117:3478?transport=udp");
        IceServerFactory f = new IceServerFactory("secret", 3600, urls, false, CloudflareTurnTestSupport.disabled());
        long before = Instant.now().getEpochSecond() + 3600;

        IceServer s = f.forUser(7L).getFirst();

        assertThat(s.urls()).isEqualTo(urls);
        String[] parts = s.username().split(":");
        assertThat(parts).hasSize(2);
        assertThat(Long.parseLong(parts[0])).isBetween(before, before + 5);
        assertThat(parts[1]).isEqualTo("7");
        assertThat(s.credential()).isBase64().isNotBlank();
    }

    @Test
    void Cloudflare_폴백은_coturn_뒤에_붙는다() {
        IceServerFactory f = new IceServerFactory(
                "s",
                60,
                List.of("turn:161.33.27.117:3478?transport=udp"),
                false,
                CloudflareTurnTestSupport.fixed(List.of(CF)));

        List<IceServer> servers = f.forUser(7L);

        assertThat(servers).hasSize(2);
        assertThat(servers.get(0).urls()).containsExactly("turn:161.33.27.117:3478?transport=udp");
        assertThat(servers.get(1)).isEqualTo(CF);
    }

    @Test
    void coturn_urls가_비어도_Cloudflare_폴백만으로_목록을_만든다() {
        IceServerFactory f =
                new IceServerFactory("s", 60, List.of(), false, CloudflareTurnTestSupport.fixed(List.of(CF)));

        assertThat(f.forUser(7L)).containsExactly(CF);
    }

    @Test
    void primary_설정이면_Cloudflare가_coturn보다_앞에_온다() {
        List<String> urls = List.of("turn:161.33.27.117:3478?transport=udp");
        IceServerFactory f = new IceServerFactory("s", 60, urls, true, CloudflareTurnTestSupport.fixed(List.of(CF)));

        List<IceServer> servers = f.forUser(7L);

        assertThat(servers.get(0)).isEqualTo(CF);
        assertThat(servers.get(1).urls()).isEqualTo(urls);
    }
}
