package project.study.group.translation;

import io.swagger.v3.oas.models.media.ComposedSchema;
import io.swagger.v3.oas.models.media.Schema;
import java.util.List;
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
            for (String name :
                    List.of("GroupHomeResponse", "PublicGroupDetailResponse", "PublicGroupCard", "MyGroupCard")) {
                Schema<?> dto = schemas.get(name);
                if (dto == null
                        || dto.getProperties() == null
                        || !dto.getProperties().containsKey("translation")) continue;
                Schema<?> reference = new Schema<>().$ref("#/components/schemas/GroupTranslation");
                ComposedSchema nullable = new ComposedSchema();
                nullable.setDescription("선택 언어 번역 — 없으면 null, 각 번역 필드도 null일 수 있어 원문으로 대체한다");
                nullable.addAnyOfItem(reference);
                Schema<?> nullType = new Schema<>();
                nullType.setTypes(Set.of("null"));
                nullable.addAnyOfItem(nullType);
                dto.getProperties().put("translation", nullable);
            }
        };
    }
}
