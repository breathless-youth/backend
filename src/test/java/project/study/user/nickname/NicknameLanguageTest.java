package project.study.user.nickname;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

/** 휴대폰 언어 태그 → 기본 닉네임 목록 (BY-1135). */
class NicknameLanguageTest {

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"  ", "ko", "ko-KR"})
    void 태그가_없거나_한국어면_한국어_목록이다(String tag) {
        assertThat(NicknameLanguage.fromTag(tag)).isEqualTo(NicknameLanguage.KO);
    }

    @ParameterizedTest
    @CsvSource({
        "ja-JP, JA",
        "ja, JA",
        "zh-Hant-HK, ZH_HK",
        "zh-HK, ZH_HK",
        "zh-MO, ZH_HK",
        "zh-Hant-MO, ZH_HK",
        "ZH-hant-hk, ZH_HK",
        "zh-Hant-TW, ZH_TW",
        "zh-TW, ZH_TW",
        "zh-Hant, ZH_TW",
        "zh_TW, ZH_TW",
        "zh-Hans-CN, ZH_CN",
        "zh-Hans-HK, ZH_CN",
        "zh-Hans-TW, ZH_CN",
        "zh-Hans-MO, ZH_CN",
        "zh-Hant-CN, ZH_CN",
        "zh-Hant-SG, ZH_CN",
        "yue-Hant-HK, ZH_HK",
        "yue, ZH_HK",
        "zh-yue-HK, ZH_HK",
        "zh-CN, ZH_CN",
        "zh-SG, ZH_CN",
        "zh, ZH_CN",
        "en-US, EN",
        "en-IN, EN",
        "hi-IN, EN",
        "es-ES, EN",
        "xx, EN",
        "!!!, EN"
    })
    void 언어_태그를_목록으로_고른다(String tag, NicknameLanguage expected) {
        assertThat(NicknameLanguage.fromTag(tag)).isEqualTo(expected);
    }
}
