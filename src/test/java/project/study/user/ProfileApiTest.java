package project.study.user;

import static org.assertj.core.api.Assertions.assertThat;
import static project.study.support.AuthTestSupport.asUser;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import project.study.TestcontainersConfiguration;
import project.study.user.nickname.AutoNicknameFixtures;
import project.study.user.nickname.AutoNicknames;
import project.study.user.nickname.NicknameLanguage;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class ProfileApiTest {

    @Autowired
    private MockMvcTester mvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbc;

    private long registerUser() {
        MvcTestResult result = mvc.post()
                .uri("/api/users")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"deviceId\":\"" + UUID.randomUUID() + "\"}")
                .exchange();
        assertThat(result).hasStatus(HttpStatus.CREATED);
        return objectMapper
                .readTree(result.getResponse().getContentAsByteArray())
                .get("userId")
                .asLong();
    }

    private long registerUser(String language) {
        MvcTestResult result = mvc.post()
                .uri("/api/users")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"deviceId\":\"" + UUID.randomUUID() + "\", \"language\":\"" + language + "\"}")
                .exchange();
        assertThat(result).hasStatus(HttpStatus.CREATED);
        return objectMapper
                .readTree(result.getResponse().getContentAsByteArray())
                .get("userId")
                .asLong();
    }

    private MvcTestResult patchNickname(long userId, String nickname) {
        return mvc.patch()
                .uri("/api/users/me/profile")
                .with(asUser(userId))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"nickname\": \"" + nickname + "\"}")
                .exchange();
    }

    private String nicknameOf(long userId) {
        return jdbc.queryForObject("select nickname from users where id = ?", String.class, userId);
    }

    private boolean nicknameAutoOf(long userId) {
        return jdbc.queryForObject("select nickname_auto from users where id = ?", Boolean.class, userId);
    }

    // 다른 테스트와 겹치지 않는 영문 닉네임 꼬리
    private static String uniqueLetters() {
        return new java.util.Random()
                .ints(6, 'a', 'z' + 1)
                .collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append)
                .toString();
    }

    // 자동 닉네임의 이니셜은 꾸미는 말의 첫 글자다
    private static List<String> modifierInitials() {
        return AutoNicknames.modifiers(NicknameLanguage.KO).stream()
                .map(m -> m.substring(0, 1))
                .toList();
    }

    @Test
    void 등록된_유저는_자동_닉네임과_프로필을_가진다() {
        long userId = registerUser();

        assertThat(mvc.get().uri("/api/users/me/profile").with(asUser(userId)))
                .hasStatusOk()
                .bodyJson()
                .hasPathSatisfying(
                        "$.nickname", v -> assertThat(v).asString().satisfies(AutoNicknameFixtures::assertAuto))
                .hasPathSatisfying("$.initial", v -> assertThat(v).asString().isIn(modifierInitials()))
                .hasPathSatisfying(
                        "$.colorIndex",
                        v -> assertThat(v)
                                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.INTEGER)
                                .isBetween(0, 7))
                .hasPathSatisfying("$.goal", v -> assertThat(v).isNull())
                .hasPathSatisfying("$.category", v -> assertThat(v).isNull())
                // 비회원은 로그인한 소셜 계정이 없다 — 그룹 탭 진입 판단에 쓴다
                .hasPathSatisfying("$.loginProvider", v -> assertThat(v).isNull());
    }

    @Test
    void 프로필을_수정하면_보낸_필드만_바뀐다() {
        long userId = registerUser();

        assertThat(mvc.patch()
                        .uri("/api/users/me/profile")
                        .with(asUser(userId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"goal\": \"올해 안에 이직 성공\"}"))
                .hasStatusOk()
                .bodyJson()
                .hasPathSatisfying("$.goal", v -> assertThat(v).isEqualTo("올해 안에 이직 성공"))
                .hasPathSatisfying(
                        "$.nickname", v -> assertThat(v).asString().satisfies(AutoNicknameFixtures::assertAuto));
    }

    private String timeZoneOf(long userId) {
        return jdbc.queryForObject("SELECT time_zone FROM users WHERE id = ?", String.class, userId);
    }

    private org.springframework.test.web.servlet.assertj.MockMvcTester.MockMvcRequestBuilder patchProfile(
            long userId, String body) {
        return mvc.patch()
                .uri("/api/users/me/profile")
                .with(asUser(userId))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body);
    }

    @Test
    void 시간대만_보내도_저장하고_응답에는_싣지_않는다() {
        long userId = registerUser();
        assertThat(timeZoneOf(userId)).isEqualTo("Asia/Seoul");

        assertThat(patchProfile(userId, "{\"timeZone\": \"America/New_York\"}"))
                .hasStatusOk()
                .bodyJson()
                .doesNotHavePath("$.timeZone")
                .hasPathSatisfying(
                        "$.nickname", v -> assertThat(v).asString().satisfies(AutoNicknameFixtures::assertAuto));
        assertThat(timeZoneOf(userId)).isEqualTo("America/New_York");
    }

    @Test
    void 읽을_수_없는_시간대는_거절하지_않고_저장된_값을_둔다() {
        long userId = registerUser();
        assertThat(patchProfile(userId, "{\"timeZone\": \"Europe/London\"}")).hasStatusOk();

        assertThat(patchProfile(userId, "{\"timeZone\": \"Mars/Olympus\", \"goal\": \"합격\"}"))
                .hasStatusOk()
                .bodyJson()
                .hasPathSatisfying("$.goal", v -> assertThat(v).isEqualTo("합격"));
        assertThat(timeZoneOf(userId)).isEqualTo("Europe/London");
    }

    @Test
    void 닉네임을_바꾸면_이니셜도_갱신된다() {
        long userId = registerUser();

        assertThat(mvc.patch()
                        .uri("/api/users/me/profile")
                        .with(asUser(userId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nickname\": \"숨벅찬청년들\"}"))
                .hasStatusOk()
                .bodyJson()
                .hasPathSatisfying("$.nickname", v -> assertThat(v).isEqualTo("숨벅찬청년들"))
                .hasPathSatisfying("$.initial", v -> assertThat(v).isEqualTo("숨"));
    }

    @Test
    void 사용_중인_닉네임으로_바꾸면_409다() {
        long first = registerUser();
        long second = registerUser();
        assertThat(mvc.patch()
                        .uri("/api/users/me/profile")
                        .with(asUser(first))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nickname\": \"점유된닉네임\"}"))
                .hasStatusOk();

        assertThat(mvc.patch()
                        .uri("/api/users/me/profile")
                        .with(asUser(second))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nickname\": \"점유된닉네임\"}"))
                .hasStatus(HttpStatus.CONFLICT);
    }

    @Test
    void 허용되지_않는_글자_공백뿐_길이_초과는_400이다() {
        long userId = registerUser();

        for (String bad : new String[] {"느낌표금지!", "홍길\u200B동", "Привет", "   ", "가".repeat(21), "a".repeat(41)}) {
            assertThat(mvc.patch()
                            .uri("/api/users/me/profile")
                            .with(asUser(userId))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"nickname\": \"" + bad + "\"}"))
                    .as("nickname=%s", bad)
                    .hasStatus(HttpStatus.BAD_REQUEST);
        }
    }

    @Test
    void 띄어쓰기와_이모지_닉네임이_저장되고_이니셜은_첫_글자_단위다() {
        long userId = registerUser();

        assertThat(mvc.patch()
                        .uri("/api/users/me/profile")
                        .with(asUser(userId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nickname\": \"  🧑\u200D💻 숨 벅찬  \"}"))
                .hasStatusOk()
                .bodyJson()
                .hasPathSatisfying("$.nickname", v -> assertThat(v).isEqualTo("🧑\u200D💻 숨 벅찬"))
                .hasPathSatisfying("$.initial", v -> assertThat(v).isEqualTo("🧑\u200D💻"));
    }

    @Test
    void 앞뒤_공백을_잘라내면_같아지는_닉네임은_409이고_본인_재설정은_200이다() {
        long first = registerUser();
        long second = registerUser();
        assertThat(mvc.patch()
                        .uri("/api/users/me/profile")
                        .with(asUser(first))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nickname\": \"정리충돌\"}"))
                .hasStatusOk();

        assertThat(mvc.patch()
                        .uri("/api/users/me/profile")
                        .with(asUser(second))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nickname\": \"  정리충돌  \"}"))
                .hasStatus(HttpStatus.CONFLICT);
        assertThat(mvc.get().uri("/api/users/me/profile").with(asUser(second)))
                .hasStatusOk()
                .bodyJson()
                .hasPathSatisfying(
                        "$.nickname", v -> assertThat(v).asString().satisfies(AutoNicknameFixtures::assertAuto));

        assertThat(mvc.patch()
                        .uri("/api/users/me/profile")
                        .with(asUser(first))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nickname\": \"  정리충돌  \"}"))
                .hasStatusOk()
                .bodyJson()
                .hasPathSatisfying("$.nickname", v -> assertThat(v).isEqualTo("정리충돌"));
    }

    @Test
    void 카테고리_미정의_값은_400이다() {
        long userId = registerUser();

        assertThat(mvc.patch()
                        .uri("/api/users/me/profile")
                        .with(asUser(userId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"category\": \"UNKNOWN\"}"))
                .hasStatus(HttpStatus.BAD_REQUEST);
    }

    @Test
    void 카테고리를_내신과_대학_시험으로_바꿀_수_있다() {
        long userId = registerUser();

        for (String category : new String[] {"SCHOOL_EXAM", "UNIVERSITY_EXAM"}) {
            assertThat(mvc.patch()
                            .uri("/api/users/me/profile")
                            .with(asUser(userId))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"category\": \"" + category + "\"}"))
                    .as("category=%s", category)
                    .hasStatusOk();
            assertThat(mvc.get().uri("/api/users/me/profile").with(asUser(userId)))
                    .hasStatusOk()
                    .bodyJson()
                    .hasPathSatisfying("$.category", v -> assertThat(v).isEqualTo(category));
        }
    }

    @Test
    void 존재하지_않는_유저의_프로필_조회는_404다() {
        assertThat(mvc.get().uri("/api/users/me/profile").with(asUser(999_999_999L)))
                .hasStatus(HttpStatus.NOT_FOUND);
    }

    @Test
    void 일본어_폰으로_등록하면_일본어_기본_닉네임이다() {
        long userId = registerUser("ja-JP");

        assertThat(AutoNicknameFixtures.all(NicknameLanguage.JA)).contains(nicknameOf(userId));
        assertThat(nicknameAutoOf(userId)).isTrue();
    }

    @Test
    void 영문_대소문자나_전각만_다른_닉네임은_409다() {
        String name = "Otter" + uniqueLetters();
        long first = registerUser();
        long second = registerUser();

        assertThat(patchNickname(first, name)).hasStatusOk();
        assertThat(patchNickname(second, name.toLowerCase(java.util.Locale.ROOT)))
                .hasStatus(HttpStatus.CONFLICT);
        String fullWidth =
                name.chars().mapToObj(c -> String.valueOf((char) (c + 0xFEE0))).reduce("", String::concat);
        assertThat(patchNickname(second, fullWidth)).hasStatus(HttpStatus.CONFLICT);
    }

    @Test
    void 지금_닉네임을_그대로_보내면_기본_닉네임_표시가_유지되고_바꾸면_꺼진다() {
        long userId = registerUser();
        String auto = nicknameOf(userId);

        assertThat(patchNickname(userId, auto)).hasStatusOk();
        assertThat(nicknameAutoOf(userId)).isTrue();

        assertThat(patchNickname(userId, "직접 지은 " + uniqueLetters())).hasStatusOk();
        assertThat(nicknameAutoOf(userId)).isFalse();
    }

    @Test
    void 금칙어_닉네임은_400_NICKNAME_NOT_ALLOWED다() {
        long userId = registerUser();

        assertThat(patchNickname(userId, "fuck"))
                .hasStatus(HttpStatus.BAD_REQUEST)
                .bodyJson()
                .extractingPath("$.code")
                .isEqualTo("NICKNAME_NOT_ALLOWED");
    }
}
