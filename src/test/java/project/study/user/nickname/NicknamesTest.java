package project.study.user.nickname;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** 닉네임 정규화(NFC + 앞뒤 공백 제거)와 글자 단위 길이·이니셜 (BY-647). */
class NicknamesTest {

    @Test
    void 앞뒤_공백을_잘라낸다() {
        assertThat(Nicknames.normalize("  홍길동  ")).isEqualTo("홍길동");
        assertThat(Nicknames.normalize("\t홍길동\n")).isEqualTo("홍길동");
    }

    @Test
    void 중간_스페이스는_연속이어도_그대로_둔다() {
        assertThat(Nicknames.normalize("홍 길  동")).isEqualTo("홍 길  동");
    }

    @Test
    void NFC로_합성해_자모_입력도_완성형이_된다() {
        assertThat(Nicknames.normalize("가나")).isEqualTo("가나");
    }

    @Test
    void 정규화는_멱등이다() {
        String once = Nicknames.normalize("  \u1100\u1161나  ");
        assertThat(once).isEqualTo("가나");
        assertThat(Nicknames.normalize(once)).isEqualTo(once);
    }

    @Test
    void null은_null이다() {
        assertThat(Nicknames.normalize(null)).isNull();
    }

    @Test
    void 길이는_눈에_보이는_글자_단위로_센다() {
        assertThat(Nicknames.length("🧑‍💻 코딩")).isEqualTo(4); // 🧑‍💻, 스페이스, 코, 딩
        assertThat(Nicknames.length("🇰🇷1️⃣")).isEqualTo(2); // 국기, 키캡
        assertThat(Nicknames.length("홍 길  동")).isEqualTo(6);
        assertThat(Nicknames.length("")).isZero();
    }

    @Test
    void 이니셜은_첫_글자_단위다() {
        assertThat(Nicknames.initialOf("🧑‍💻코딩")).isEqualTo("🧑‍💻");
        assertThat(Nicknames.initialOf("홍길동")).isEqualTo("홍");
    }
}
