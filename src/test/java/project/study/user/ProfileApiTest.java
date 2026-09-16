package project.study.user;

import static org.assertj.core.api.Assertions.assertThat;
import static project.study.support.AuthTestSupport.asUser;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import project.study.TestcontainersConfiguration;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class ProfileApiTest {

    @Autowired
    private MockMvcTester mvc;

    @Autowired
    private ObjectMapper objectMapper;

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

    @Test
    void 등록된_유저는_자동_닉네임과_프로필을_가진다() {
        long userId = registerUser();

        assertThat(mvc.get().uri("/api/users/me/profile").with(asUser(userId)))
                .hasStatusOk()
                .bodyJson()
                .hasPathSatisfying("$.nickname", v -> assertThat(v).asString().matches("포메\\d{5}"))
                .hasPathSatisfying("$.initial", v -> assertThat(v).isEqualTo("포"))
                .hasPathSatisfying(
                        "$.colorIndex",
                        v -> assertThat(v)
                                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.INTEGER)
                                .isBetween(0, 7))
                .hasPathSatisfying("$.goal", v -> assertThat(v).isNull())
                .hasPathSatisfying("$.category", v -> assertThat(v).isNull());
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
                .hasPathSatisfying("$.nickname", v -> assertThat(v).asString().matches("포메\\d{5}"));
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

        for (String bad : new String[] {"느낌표금지!", "홍길\u200B동", "日本語", "   ", "a", "가나다라마바사아자차카타파"}) {
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
                .hasPathSatisfying("$.nickname", v -> assertThat(v).asString().matches("포메\\d{5}"));

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
    void 존재하지_않는_유저의_프로필_조회는_404다() {
        assertThat(mvc.get().uri("/api/users/me/profile").with(asUser(999_999_999L)))
                .hasStatus(HttpStatus.NOT_FOUND);
    }
}
