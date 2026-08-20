package project.study.room;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import project.study.TestcontainersConfiguration;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class RoomApiTest {

    @Autowired
    private MockMvcTester mvc;

    @Test
    void 룸에_입장하면_200과_응답이_내려온다() {
        assertThat(mvc.post()
                        .uri("/api/rooms/1/enter")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\": 1}"))
                .hasStatusOk()
                .bodyJson()
                .hasPathSatisfying("$.graceRejoin", v -> assertThat(v).isEqualTo(false))
                .hasPathSatisfying("$.iceTtlSeconds", v -> assertThat(v).isNotNull());
    }

    @Test
    void 정원_초과_시_409를_반환한다() {
        for (int i = 1; i <= 8; i++) {
            assertThat(mvc.post()
                            .uri("/api/rooms/99/enter")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"userId\": " + (100 + i) + "}"))
                    .hasStatusOk();
        }

        assertThat(mvc.post()
                        .uri("/api/rooms/99/enter")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\": 200}"))
                .hasStatus(HttpStatus.CONFLICT);
    }

    @Test
    void 퇴장하면_204를_반환한다() {
        assertThat(mvc.post()
                        .uri("/api/rooms/2/enter")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\": 50}"))
                .hasStatusOk();

        assertThat(mvc.post().uri("/api/rooms/2/leave").param("userId", "50")).hasStatus(HttpStatus.NO_CONTENT);
    }

    @Test
    void userId_없이_입장하면_400을_반환한다() {
        assertThat(mvc.post()
                        .uri("/api/rooms/1/enter")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .hasStatus(HttpStatus.BAD_REQUEST);
    }
}
