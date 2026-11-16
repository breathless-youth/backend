package project.study.group.translation;

import io.swagger.v3.oas.models.media.ComposedSchema;
import io.swagger.v3.oas.models.media.Schema;
import java.util.Map;
import java.util.Set;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 이 프로젝트의 OpenAPI 3.1에서 활성화 전에도 FE가 nullable 번역 타입을 생성할 수 있게 한다. */
@Configuration(proxyBeanMethods = false)
public class TranslationOpenApiConfig {
    @Bean
    OpenApiCustomizer nullableGroupTranslation() {
        return api -> {
            if (api.getComponents() == null || api.getComponents().getSchemas() == null) return;
            var schemas = api.getComponents().getSchemas();
            // DTO 이름 → translation 타입. 신청 소개 글 번역은 BY-1301
            Map<String, String> translations = Map.of(
                    "GroupHomeResponse", "GroupTranslation",
                    "PublicGroupDetailResponse", "GroupTranslation",
                    "PublicGroupCard", "GroupTranslation",
                    "MyGroupCard", "GroupTranslation",
                    "GroupApplicationItem", "GroupApplicationTranslation");
            for (var entry : translations.entrySet()) {
                Schema<?> dto = schemas.get(entry.getKey());
                if (dto == null
                        || dto.getProperties() == null
                        || !dto.getProperties().containsKey("translation")) continue;
                Schema<?> reference = new Schema<>().$ref("#/components/schemas/" + entry.getValue());
                ComposedSchema nullable = new ComposedSchema();
                nullable.setDescription("선택 언어 번역 — 없으면 null이고 원문으로 대체한다");
                nullable.addAnyOfItem(reference);
                Schema<?> nullType = new Schema<>();
                nullType.setTypes(Set.of("null"));
                nullable.addAnyOfItem(nullType);
                dto.getProperties().put("translation", nullable);
            }
        };
    }
}
