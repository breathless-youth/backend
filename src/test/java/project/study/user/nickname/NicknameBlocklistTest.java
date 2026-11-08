package project.study.user.nickname;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

/** 닉네임 금칙어·사칭 이름 (BY-1135). 입력은 저장 때와 같이 정규화한 값이다. */
class NicknameBlocklistTest {

    private static boolean blocked(String raw) {
        return NicknameBlocklist.isBlocked(Nicknames.normalize(raw));
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "fuck",
                "F u c k",
                "f_u_c_k",
                "f.u.c.k",
                "FUCKYOU",
                "ＦＵＣＫ",
                "sh1t",
                "Big Ass",
                "bitch otter",
                "씨발",
                "씨 발",
                "개새끼",
                "병신 수달",
                "傻逼",
                "他妈的",
                "他媽的",
                "幹你娘",
                "fück",
                "ƒuck",
                "ちんこ",
                "🖕"
            })
    void 욕설과_우회_표기를_막는다(String nickname) {
        assertThat(blocked(nickname)).as(nickname).isTrue();
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "admin",
                "Admin",
                "운영자",
                "official",
                "Official Otter",
                "管理员",
                "公式",
                "포메",
                "포메 공식",
                "포메운영팀",
                "공식 포메",
                "포메 관리자님",
                "포메공식계정",
                "포메팀장",
                "수달운영자",
                "官方客服",
                "運営チーム",
                "管理者さん",
                "admın",
                "Ädmin"
            })
    void 운영자로_보이는_이름을_막는다(String nickname) {
        assertThat(blocked(nickname)).as(nickname).isTrue();
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "Scunthorpe",
                "assassin",
                "Cocktail",
                "Grape",
                "therapist",
                "Matsushita",
                "Shiitake Otter",
                "시발점",
                "시바견",
                "고자질쟁이",
                "포메라니안",
                "껌 씹는 고양이",
                "Classic Otter",
                "José Otter",
                "妈妈的小熊",
                "다시 발견한 수달",
                "미친듯이 공부하는 수달",
                "학자 지망생",
                "혼자 지내는 수달",
                "5개년 계획 세우는 수달",
                "날개 새",
                "정보지",
                "불알친구",
                "변태하는 올챙이",
                "Solar System",
                "Support Otter",
                "奶茶",
                "个性"
            })
    void 정상_단어_속에_우연히_든_금칙어는_막지_않는다(String nickname) {
        assertThat(blocked(nickname)).as(nickname).isFalse();
    }

    @ParameterizedTest
    @EnumSource(NicknameLanguage.class)
    void 기본_닉네임은_하나도_막히지_않는다(NicknameLanguage language) {
        assertThat(AutoNicknameFixtures.all(language)).noneMatch(NicknameBlocklist::isBlocked);
    }
}
