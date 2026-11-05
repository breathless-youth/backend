package project.study.planner;

import static org.assertj.core.api.Assertions.assertThat;
import static project.study.support.AuthTestSupport.asUser;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MockMvcTester.MockMvcRequestBuilder;
import project.study.TestcontainersConfiguration;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
abstract class PlannerApiSupport {
    @Autowired
    protected MockMvcTester mvc;

    @Autowired
    protected JdbcTemplate jdbc;

    @Autowired
    protected ObjectMapper json;

    protected long user;
    protected long subject;
    protected LocalDate today;

    @BeforeEach
    void setupPlanner() {
        user = newUser();
        subject = jdbc.queryForObject(
                "INSERT INTO study_subject(user_id,name) VALUES (?, '수학') RETURNING id", Long.class, user);
        ZonedDateTime now = ZonedDateTime.now(ZoneId.of("Asia/Seoul"));
        today = now.toLocalTime().isBefore(LocalTime.of(5, 0))
                ? now.toLocalDate().minusDays(1)
                : now.toLocalDate();
    }

    protected long newUser() {
        return jdbc.queryForObject(
                "INSERT INTO users(provider,provider_user_id,nickname) VALUES ('test',?,?) RETURNING id",
                Long.class,
                UUID.randomUUID().toString(),
                UUID.randomUUID().toString());
    }

    protected JsonNode call(String method, String uri, String body, int status) {
        MockMvcRequestBuilder request =
                switch (method) {
                    case "POST" -> mvc.post();
                    case "PUT" -> mvc.put();
                    case "PATCH" -> mvc.patch();
                    case "DELETE" -> mvc.delete();
                    default -> mvc.get();
                };
        request.uri(uri).with(asUser(user)).header("API-Version", "1");
        if (body != null) request.contentType(MediaType.APPLICATION_JSON).content(body);
        var result = request.exchange();
        assertThat(result.getResponse().getStatus())
                .as(new String(result.getResponse().getContentAsByteArray(), java.nio.charset.StandardCharsets.UTF_8))
                .isEqualTo(status);
        byte[] bytes = result.getResponse().getContentAsByteArray();
        return bytes.length == 0 ? null : json.readTree(bytes);
    }

    protected String add(LocalDate date, String name) {
        return call(
                        "POST",
                        "/api/planner/tasks",
                        "{\"subjectId\":" + subject + ",\"date\":\"" + date + "\",\"name\":\"" + name + "\"}",
                        201)
                .get("key")
                .asText();
    }

    protected JsonNode day(LocalDate date) {
        return call("GET", "/api/planner/days/" + date, null, 200);
    }
}
