package project.study.user.nickname;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** 허용 목록(한글·영문·숫자·이모지·띄어쓰기) + 앞뒤 공백 제외 2~12자 (BY-647). */
class NicknameValidatorTest {

    private final NicknameValidator validator = new NicknameValidator();

    private boolean valid(String nickname) {
        return validator.isValid(nickname, null);
    }

    @Test
    void 한글_영문_숫자_이모지_띄어쓰기를_허용한다() {
        assertThat(valid("숨 벅찬 청년들")).isTrue();
        assertThat(valid("Study7")).isTrue();
        assertThat(valid("코딩🧑‍💻")).isTrue();
        assertThat(valid("🇰🇷 화이팅")).isTrue();
        assertThat(valid("1️⃣등 목표")).isTrue();
        assertThat(valid("👩🏽‍💻 개발")).isTrue(); // 피부색 + ZWJ
        assertThat(valid("❤️‍🔥 열정")).isTrue(); // 불타는 하트
        assertThat(valid("🏴󠁧󠁢󠁥󠁮󠁧󠁿 잉글랜드")).isTrue();
        assertThat(valid("a1")).isTrue();
    }

    @Test
    void 앞뒤_공백은_잘라낸_뒤_길이를_잰다() {
        assertThat(valid("  홍길동  ")).isTrue();
        assertThat(valid(" a ")).isFalse(); // 잘라내면 1자
    }

    @Test
    void 자모_입력은_NFC로_합성되어_허용된다() {
        assertThat(valid("가나")).isTrue(); // 가나
    }

    @Test
    void 문장부호_다른_언어_자음모음_단독은_거부한다() {
        assertThat(valid("느낌표금지!")).isFalse();
        assertThat(valid("日本語ニック")).isFalse();
        assertThat(valid("ㅋㅋㅋ")).isFalse();
        assertThat(valid("under_score")).isFalse();
    }

    @Test
    void 보이지_않는_글자는_지우지_않고_거부한다() {
        assertThat(valid("홍길​동")).isFalse(); // 제로폭 공백
        assertThat(valid("홍길‍동")).isFalse(); // 단독 ZWJ
        assertThat(valid("홍길 동")).isFalse(); // NBSP
        assertThat(valid("홍길\t동")).isFalse();
        assertThat(valid("😀‍a")).isFalse(); // 이모지 뒤에 남은 ZWJ
        assertThat(valid("😀‌a")).isFalse(); // ZWNJ
        assertThat(valid("ㅤㅤ")).isFalse(); // 한글 채움 문자
    }

    @Test
    void 이모지_조합은_부품이_전부_이모지일_때만_인정한다() {
        assertThat(valid("🇰 국기")).isFalse(); // 지역 지시자 하나
        assertThat(valid("😀́ 악센트")).isFalse(); // 이모지 + 결합기호
        assertThat(valid("🏴󠁿 깃발")).isFalse(); // 종료 태그만
        assertThat(valid("a️b")).isFalse(); // 글자에 붙은 표현 선택자
    }

    @Test
    void 길이_경계는_2자와_12자다() {
        assertThat(valid("가나")).isTrue();
        assertThat(valid("가나다라마바사아자차카타")).isTrue(); // 12자
        assertThat(valid("가")).isFalse();
        assertThat(valid("가나다라마바사아자차카타파")).isFalse(); // 13자
        assertThat(valid("🇰🇷".repeat(12))).isTrue(); // 국기 12개 = 12자
        assertThat(valid("🇰🇷".repeat(13))).isFalse();
    }

    @Test
    void 공백뿐이거나_비면_거부한다() {
        assertThat(valid("")).isFalse();
        assertThat(valid("   ")).isFalse();
    }

    @Test
    void 원문_길이_상한은_256자다() {
        assertThat(valid(" ".repeat(254) + "가나")).isTrue(); // 256자 — 앞 공백은 잘려 "가나"
        assertThat(valid(" ".repeat(255) + "가나")).isFalse(); // 257자 — 정규화 전에 거부
    }

    @Test
    void 코드포인트_상한은_100이다() {
        // 😀 + (ZWJ 😀) 반복은 한 글자 단위로 이어지므로 글자 수로는 못 막고 코드포인트 수로 막는다
        assertThat(valid("😀" + "\u200D😀".repeat(48) + " a")).isTrue(); // 1 + 96 + 2 = 99 코드포인트
        assertThat(valid("😀" + "\u200D😀".repeat(49) + " a")).isFalse(); // 101 코드포인트
        assertThat(valid("👨\u200D👩\u200D👧\u200D👦".repeat(12))).isTrue(); // 가족 12개 = 84 코드포인트
    }

    @Test
    void 미할당_코드포인트는_이모지로_보지_않는다() {
        assertThat(valid("\uD83F\uDC00 a")).isFalse(); // U+1FC00: Extended_Pictographic이지만 미할당
        assertThat(valid("© 저작권")).isTrue(); // 텍스트 기본 표시 기호도 이모지 속성이라 허용
    }

    @Test
    void null은_미변경이라_통과한다() {
        assertThat(valid(null)).isTrue();
    }
}
