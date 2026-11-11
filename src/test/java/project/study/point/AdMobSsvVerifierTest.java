package project.study.point;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.security.PublicKey;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import project.study.point.service.AdMobKeySource;
import project.study.point.service.AdMobSsvVerifier;

class AdMobSsvVerifierTest {

    private final AdMobSigner signer = new AdMobSigner();
    private final MutableClock clock = new MutableClock();
    private final AtomicInteger fetches = new AtomicInteger();
    private Map<Long, PublicKey> served = Map.of(AdMobSigner.KEY_ID, signer.publicKey());
    private boolean keyServerDown;

    private final AdMobKeySource source = () -> {
        fetches.incrementAndGet();
        if (keyServerDown) {
            throw new IllegalStateException("down");
        }
        return served;
    };
    private final AdMobSsvVerifier verifier = new AdMobSsvVerifier(source, clock);

    @Test
    void 서명이_맞으면_서명된_값을_준다() {
        String query = signer.signed(signer.content("2747237135", "42", "abc"));

        assertThat(verifier.verify(query)).hasValueSatisfying(params -> {
            assertThat(params).containsEntry("ad_unit", "2747237135");
            assertThat(params).containsEntry("user_id", "42");
            assertThat(params).containsEntry("transaction_id", "abc");
            assertThat(params).containsEntry("custom_data", "");
        });
    }

    @Test
    void 서명된_내용을_바꾸면_거절한다() {
        String query = signer.signed(signer.content("2747237135", "42", "abc"));

        assertThat(verifier.verify(query.replace("user_id=42", "user_id=43"))).isEmpty();
        // 서명 뒤에 값을 덧붙여도 서명된 내용으로 치지 않는다
        assertThat(verifier.verify(query + "&user_id=43")).isEmpty();
    }

    @Test
    void 다른_키로_서명했거나_형식이_틀리면_거절한다() {
        String content = signer.content("2747237135", "42", "abc");

        assertThat(verifier.verify(new AdMobSigner().signed(content))).isEmpty();
        assertThat(verifier.verify(content)).isEmpty();
        assertThat(verifier.verify(content + "&signature=!!!&key_id=" + AdMobSigner.KEY_ID))
                .isEmpty();
        assertThat(verifier.verify(content + "&signature=AAAA&key_id=abc")).isEmpty();
        assertThat(verifier.verify(null)).isEmpty();
    }

    @Test
    void 키는_들고_있다가_하루가_지나면_다시_받는다() {
        String query = signer.signed(signer.content("2747237135", "42", "abc"));

        verifier.verify(query);
        verifier.verify(query);
        assertThat(fetches).hasValue(1);

        clock.advance(Duration.ofHours(25));
        assertThat(verifier.verify(query)).isPresent();
        assertThat(fetches).hasValue(2);
    }

    @Test
    void 모르는_키_ID는_몇_초에_한_번만_다시_받는다() {
        String known = signer.signed(signer.content("2747237135", "42", "abc"));
        String unknown = known.replace("key_id=" + AdMobSigner.KEY_ID, "key_id=1");
        verifier.verify(known);

        clock.advance(Duration.ofSeconds(1));
        assertThat(verifier.verify(unknown)).isEmpty();
        assertThat(fetches).hasValue(1);

        clock.advance(Duration.ofSeconds(5));
        assertThat(verifier.verify(unknown)).isEmpty();
        assertThat(verifier.verify(unknown)).isEmpty();
        assertThat(fetches).hasValue(2);
    }

    @Test
    void 새_키로_바뀌면_구글이_다시_보내는_몇_초_안에_받아_온다() {
        verifier.verify(signer.signed(signer.content("2747237135", "42", "abc")));
        AdMobSigner rotated = new AdMobSigner();
        served = Map.of(2L, rotated.publicKey());
        String query = rotated.signed(rotated.content("2747237135", "42", "def"))
                .replace("key_id=" + AdMobSigner.KEY_ID, "key_id=2");

        clock.advance(Duration.ofSeconds(1));
        assertThat(verifier.verify(query)).isEmpty();
        clock.advance(Duration.ofSeconds(3));
        assertThat(verifier.verify(query)).isPresent();
    }

    @Test
    void 하루가_지난_키는_다시_받지_못하면_쓰지_않고_실패한_시도도_몇_초에_한_번이다() {
        String query = signer.signed(signer.content("2747237135", "42", "abc"));
        verifier.verify(query);

        keyServerDown = true;
        clock.advance(Duration.ofHours(25));
        assertThatThrownBy(() -> verifier.verify(query)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> verifier.verify(query)).isInstanceOf(IllegalStateException.class);
        assertThat(fetches).hasValue(2);

        keyServerDown = false;
        clock.advance(Duration.ofSeconds(4));
        assertThat(verifier.verify(query)).isPresent();
    }

    @Test
    void 실패한_다시_받기는_들고_있는_키의_수명을_늘리지_않는다() {
        String known = signer.signed(signer.content("2747237135", "42", "abc"));
        verifier.verify(known);

        keyServerDown = true;
        clock.advance(Duration.ofHours(23));
        assertThat(verifier.verify(known.replace("key_id=" + AdMobSigner.KEY_ID, "key_id=1")))
                .isEmpty();
        assertThat(verifier.verify(known)).isPresent();

        clock.advance(Duration.ofHours(2));
        assertThatThrownBy(() -> verifier.verify(known)).isInstanceOf(IllegalStateException.class);
    }

    private static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-11-12T00:00:00Z");

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
