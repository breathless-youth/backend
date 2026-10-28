package project.study.acquisition.referrer;

import static org.assertj.core.api.Assertions.assertThat;
import static project.study.acquisition.MetaReferrerFixture.CIPHER;
import static project.study.acquisition.MetaReferrerFixture.KEY;
import static project.study.acquisition.MetaReferrerFixture.NONCE;
import static project.study.acquisition.MetaReferrerFixture.PLAIN;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

@ExtendWith(OutputCaptureExtension.class)
class MetaReferrerDecryptorTest {

    @Test
    void 메타_문서_예시가_풀린다() {
        assertThat(new MetaReferrerDecryptor(KEY).decrypt(CIPHER, NONCE)).contains(PLAIN);
    }

    @Test
    void 다른_키면_빈값이고_키는_로그에_없다(CapturedOutput output) {
        String otherKey = "00".repeat(32);
        assertThat(new MetaReferrerDecryptor(otherKey).decrypt(CIPHER, NONCE)).isEmpty();
        assertThat(output).contains("AEADBadTagException").doesNotContain(otherKey);
    }

    @Test
    void hex가_아니면_빈값() {
        MetaReferrerDecryptor decryptor = new MetaReferrerDecryptor(KEY);
        assertThat(decryptor.decrypt("zz" + CIPHER, NONCE)).isEmpty();
        assertThat(decryptor.decrypt(CIPHER.substring(1), NONCE)).isEmpty(); // 홀수 길이
        assertThat(decryptor.decrypt(CIPHER, "abcd")).isEmpty(); // nonce 12바이트 아님
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"  ", "abc", "2575590594a9cd80"})
    void 키가_없거나_64자_hex가_아니면_꺼진다(String key, CapturedOutput output) {
        MetaReferrerDecryptor decryptor = new MetaReferrerDecryptor(key);
        assertThat(decryptor.enabled()).isFalse();
        assertThat(decryptor.decrypt(CIPHER, NONCE)).isEmpty();
        assertThat(output).contains("meta-install-referrer-key");
    }

    @Test
    void 키가_맞으면_켜진다() {
        assertThat(new MetaReferrerDecryptor(KEY).enabled()).isTrue();
    }

    @Test
    void 키_앞뒤_공백과_개행은_떼고_켜진다() {
        MetaReferrerDecryptor decryptor = new MetaReferrerDecryptor("  " + KEY + "\n");
        assertThat(decryptor.enabled()).isTrue();
        assertThat(decryptor.decrypt(CIPHER, NONCE)).contains(PLAIN);
    }

    @Test
    void 켜지면_INFO_한_줄을_남기고_키는_남기지_않는다(CapturedOutput output) {
        new MetaReferrerDecryptor(KEY);
        assertThat(output).contains("메타 설치 정보 복호화 켜짐").doesNotContain(KEY);
    }
}
