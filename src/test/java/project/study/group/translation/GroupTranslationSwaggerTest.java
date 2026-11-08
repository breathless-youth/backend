package project.study.group.translation;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import project.study.TestcontainersConfiguration;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class GroupTranslationSwaggerTest {
    @Autowired
    MockMvcTester mvc;

    @Test
    void generatedSwaggerDescribesNullableTranslationAndFields() throws Exception {
        var response = mvc.get().uri("/v3/api-docs").exchange().getResponse();
        assertThat(response.getStatus()).isEqualTo(200);
        var document = JsonMapper.builder().build().readTree(response.getContentAsString());
        assertThat(document.path("openapi").asString()).startsWith("3.1");
        var schemas = document.path("components").path("schemas");
        var fields = schemas.path("GroupTranslation").path("properties");
        assertThat(fields.path("name").path("type").toString()).contains("\"string\"");
        assertThat(fields.path("description").path("type").toString()).contains("\"string\"");
        assertThat(nullable(fields.path("name")))
                .as("translation.name nullable in generated schema")
                .isTrue();
        assertThat(nullable(fields.path("description")))
                .as("translation.description nullable in generated schema")
                .isTrue();
        for (String dto : List.of("GroupHomeResponse", "PublicGroupDetailResponse", "PublicGroupCard", "MyGroupCard")) {
            assertThat(schemas.path(dto).path("properties").path("translation").toString())
                    .contains("#/components/schemas/GroupTranslation");
            assertThat(nullable(schemas.path(dto).path("properties").path("translation")))
                    .as(
                            "%s.translation: %s",
                            dto, schemas.path(dto).path("properties").path("translation"))
                    .isTrue();
        }
        assertThat(fields.path("language").path("enum").toString()).isEqualTo("[\"ko\",\"en\",\"ja\",\"zh-TW\"]");
    }

    private static boolean nullable(JsonNode schema) {
        if (schema.has("$ref") || schema.has("allOf")) return false;
        if (schema.path("type").isString() && schema.path("type").asString().equals("null")) return true;
        for (var type : schema.path("type")) if (type.asString("").equals("null")) return true;
        for (String union : List.of("anyOf", "oneOf")) {
            for (var option : schema.path(union)) if (nullable(option)) return true;
        }
        return false;
    }
}
