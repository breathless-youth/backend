package project.study.room;

import static org.assertj.core.api.Assertions.assertThat;

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

/**
 * 구 앱(v1.2.x) 룸 계약 — 헤더·토큰 없이 본문/쿼리의 userId로 식별한다 (ADR-0020).
 * 룸 규칙(정원·초대코드 형식 등)은 서비스가 v2와 같으므로 여기서는 userId 채널만 본다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class LegacyRoomApiTest {

    @Autowired
    private MockMvcTester mvc;

    @Autowired
    private ObjectMapper objectMapper;

    private long registerUser() {
        MvcTestResult result = mvc.post()
                .uri("/api/users")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"deviceId\": \"" + UUID.randomUUID() + "\"}")
                .exchange();
        assertThat(result).hasStatus(HttpStatus.CREATED);
        return objectMapper
                .readTree(result.getResponse().getContentAsByteArray())
                .get("userId")
                .asLong();
    }

    private MvcTestResult createRoom(String body) {
        return mvc.post()
                .uri("/api/rooms")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
                .exchange();
    }

    private String createRoomAndGetCode(long userId) {
        MvcTestResult result = createRoom("{\"userId\": " + userId + "}");
        assertThat(result).hasStatus(HttpStatus.CREATED);
        return objectMapper
                .readTree(result.getResponse().getContentAsByteArray())
                .get("inviteCode")
                .asString();
    }

    private MvcTestResult join(long userId, String inviteCode) {
        return mvc.post()
                .uri("/api/rooms/join")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"userId\": " + userId + ", \"inviteCode\": \"" + inviteCode + "\"}")
                .exchange();
    }

    @Test
    void 본문_userId로_방을_만들면_201과_초대코드가_내려온다() {
        long userId = registerUser();

        assertThat(createRoom("{\"userId\": " + userId + "}"))
                .hasStatus(HttpStatus.CREATED)
                .bodyJson()
                .hasPathSatisfying("$.inviteCode", v -> assertThat(v).asString().matches("\\d{4}"));
    }

    @Test
    void userId_없이_방을_만들면_400이다() {
        assertThat(createRoom("{}")).hasStatus(HttpStatus.BAD_REQUEST);
    }

    @Test
    void 등록되지_않은_userId는_방을_만들_수_없다() {
        assertThat(createRoom("{\"userId\": 999999999}")).hasStatus(HttpStatus.NOT_FOUND);
    }

    @Test
    void 본문_userId와_초대코드로_입장하면_200과_roomId가_내려온다() {
        String code = createRoomAndGetCode(registerUser());

        assertThat(join(registerUser(), code))
                .hasStatusOk()
                .bodyJson()
                .hasPathSatisfying("$.roomId", v -> assertThat(v).isNotNull());
    }

    @Test
    void 쿼리_userId로_퇴장하면_204다() {
        String code = createRoomAndGetCode(registerUser());
        long userId = registerUser();
        MvcTestResult joined = join(userId, code);
        assertThat(joined).hasStatusOk();
        long roomId = objectMapper
                .readTree(joined.getResponse().getContentAsByteArray())
                .get("roomId")
                .asLong();

        assertThat(mvc.post().uri("/api/rooms/" + roomId + "/leave").param("userId", String.valueOf(userId)))
                .hasStatus(HttpStatus.NO_CONTENT);
    }

    @Test
    void 퇴장에_userId가_없으면_400이다() {
        assertThat(mvc.post().uri("/api/rooms/1/leave")).hasStatus(HttpStatus.BAD_REQUEST);
    }
}
