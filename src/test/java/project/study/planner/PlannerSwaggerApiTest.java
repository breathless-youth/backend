package project.study.planner;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

class PlannerSwaggerApiTest extends PlannerApiSupport {
    @Test
    void plannerRequestResponseAndErrorExamplesArePresentInGeneratedOpenApi() {
        JsonNode api = call("GET", "/v3/api-docs", null, 200);
        JsonNode paths = api.get("paths");
        for (String path : List.of(
                "/api/planner",
                "/api/planner/days/{date}",
                "/api/planner/summary",
                "/api/planner/tasks",
                "/api/planner/tasks/{key}/repeat",
                "/api/planner/carryover",
                "/api/planner/goals/default",
                "/api/planner/goals/{date}",
                "/api/planner/records/batch")) {
            assertThat(paths.has(path)).as(path).isTrue();
        }
        JsonNode tasks = paths.get("/api/planner/tasks").get("post");
        assertExamples(tasks.get("requestBody"));
        assertExamples(tasks.get("responses").get("201"));
        assertExamples(tasks.get("responses").get("409"));
        JsonNode batch = paths.get("/api/planner/records/batch").get("post");
        assertExamples(batch.get("requestBody"));
        assertExamples(batch.get("responses").get("200"));
        assertExamples(paths.get("/api/planner/goals/{date}")
                .get("get")
                .get("responses")
                .get("200"));
        assertExamples(
                paths.get("/api/planner/summary").get("get").get("responses").get("200"));
    }

    private static void assertExamples(JsonNode body) {
        assertThat(body).isNotNull();
        assertThat(body.get("content")).isNotNull();
        boolean hasExample = false;
        for (JsonNode media : body.get("content")) {
            if (media.has("example")
                    || (media.has("examples") && !media.get("examples").isEmpty())) hasExample = true;
        }
        assertThat(hasExample).as(body.toString()).isTrue();
    }
}
