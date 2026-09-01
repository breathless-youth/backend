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

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class RoomApiTest {

    @Autowired
    private MockMvcTester mvc;

    @Autowired
    private ObjectMapper objectMapper;

    // join이 프로필(닉네임·목표)을 조회해 방 상태에 보관하므로 실제 유저가 필요하다
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

    private String createRoomAndGetCode(long userId) {
        MvcTestResult result = mvc.post()
                .uri("/api/rooms")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"userId\": " + userId + "}")
                .exchange();
        assertThat(result).hasStatus(HttpStatus.CREATED);
        return objectMapper
                .readTree(result.getResponse().getContentAsByteArray())
                .get("inviteCode")
                .asString();
    }

    private MockMvcTester.MockMvcRequestBuilder joinRequest(long userId, String inviteCode) {
        return mvc.post()
                .uri("/api/rooms/join")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"userId\": " + userId + ", \"inviteCode\": \"" + inviteCode + "\"}");
    }

    @Test
    void 방을_만들면_201과_초대코드가_내려온다() {
        assertThat(mvc.post()
                        .uri("/api/rooms")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\": 1}"))
                .hasStatus(HttpStatus.CREATED)
                .bodyJson()
                .hasPathSatisfying("$.roomId", v -> assertThat(v).isNotNull())
                .hasPathSatisfying("$.inviteCode", v -> assertThat(v).asString().matches("\\d{4}"))
                .hasPathSatisfying("$.emptyTtlSeconds", v -> assertThat(v).isEqualTo(600));
    }

    @Test
    void 초대코드로_입장하면_200과_응답이_내려온다() {
        String code = createRoomAndGetCode(1L);
        long userId = registerUser();

        assertThat(joinRequest(userId, code))
                .hasStatusOk()
                .bodyJson()
                .hasPathSatisfying("$.roomId", v -> assertThat(v).isNotNull())
                .hasPathSatisfying("$.graceRejoin", v -> assertThat(v).isEqualTo(false))
                .hasPathSatisfying("$.iceTtlSeconds", v -> assertThat(v).isNotNull());
    }

    @Test
    void 형식이_틀린_초대코드는_400이다() {
        long userId = registerUser();

        assertThat(joinRequest(userId, "12a4")).hasStatus(HttpStatus.BAD_REQUEST);
    }

    @Test
    void 없는_초대코드는_404다() {
        String code = createRoomAndGetCode(1L);
        long userId = registerUser();
        // 활성 코드와 겹치지 않는 코드를 찾는다
        String missing = code.equals("0000") ? "0001" : "0000";

        assertThat(joinRequest(userId, missing)).hasStatus(HttpStatus.NOT_FOUND);
    }

    @Test
    void 등록되지_않은_유저가_입장하면_404다() {
        String code = createRoomAndGetCode(1L);

        assertThat(joinRequest(999_999_999L, code))
                .hasStatus(HttpStatus.NOT_FOUND)
                .bodyJson()
                .hasPathSatisfying("$.code", v -> assertThat(v).isEqualTo("USER_NOT_FOUND"));
    }

    @Test
    void 정원_6명_초과_시_409를_반환한다() {
        String code = createRoomAndGetCode(1L);
        for (int i = 1; i <= 6; i++) {
            assertThat(joinRequest(registerUser(), code)).hasStatusOk();
        }

        assertThat(joinRequest(registerUser(), code)).hasStatus(HttpStatus.CONFLICT);
    }

    @Test
    void 퇴장하면_204를_반환한다() {
        String code = createRoomAndGetCode(1L);
        long userId = registerUser();
        MvcTestResult joined = joinRequest(userId, code).exchange();
        assertThat(joined).hasStatusOk();
        long roomId = objectMapper
                .readTree(joined.getResponse().getContentAsByteArray())
                .get("roomId")
                .asLong();

        assertThat(mvc.post().uri("/api/rooms/" + roomId + "/leave").param("userId", String.valueOf(userId)))
                .hasStatus(HttpStatus.NO_CONTENT);
    }

    @Test
    void 마지막_퇴장_후_같은_코드로_입장하면_404다() {
        String code = createRoomAndGetCode(1L);
        long userId = registerUser();
        MvcTestResult joined = joinRequest(userId, code).exchange();
        long roomId = objectMapper
                .readTree(joined.getResponse().getContentAsByteArray())
                .get("roomId")
                .asLong();
        assertThat(mvc.post().uri("/api/rooms/" + roomId + "/leave").param("userId", String.valueOf(userId)))
                .hasStatus(HttpStatus.NO_CONTENT);

        assertThat(joinRequest(registerUser(), code))
                .hasStatus(HttpStatus.NOT_FOUND)
                .bodyJson()
                .hasPathSatisfying("$.code", v -> assertThat(v).isEqualTo("ROOM_CLOSED"));
    }

    @Test
    void userId_없이_방을_만들면_400이다() {
        assertThat(mvc.post()
                        .uri("/api/rooms")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .hasStatus(HttpStatus.BAD_REQUEST);
    }
}
