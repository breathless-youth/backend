package project.study.common.category;

import static org.assertj.core.api.Assertions.assertThat;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Pattern;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import project.study.metrics.report.Categories;
import project.study.user.dto.ProfileUpdateRequest;

/** 시험·목표 9개 — 프로필 허용값·리포트 라벨이 enum과 어긋나지 않는지 (BY-1124). */
class StudyCategoryTest {

    @Test
    void 아홉_개가_정해진_순서와_라벨을_가진다() {
        assertThat(StudyCategory.values())
                .extracting(Enum::name)
                .containsExactly(
                        "CSAT",
                        "SCHOOL_EXAM",
                        "UNIVERSITY_EXAM",
                        "CIVIL_SERVICE",
                        "CERTIFICATE",
                        "LANGUAGE",
                        "JOB",
                        "PROFESSIONAL",
                        "ETC");
        assertThat(StudyCategory.values())
                .extracting(StudyCategory::label)
                .containsExactly("수능", "내신", "대학 시험", "공무원", "자격증", "어학", "취업", "전문직", "기타");
    }

    @Test
    void 프로필_허용값과_리포트_라벨이_enum과_같다() throws NoSuchFieldException {
        // @Pattern은 RECORD_COMPONENT 대상이 아니라 RecordComponent로는 읽히지 않는다 — 필드에서 읽는다
        String regexp = ProfileUpdateRequest.class
                .getDeclaredField("category")
                .getAnnotation(Pattern.class)
                .regexp();
        Set<String> allowed = Set.of(regexp.split("\\|"));
        Set<String> names =
                Arrays.stream(StudyCategory.values()).map(Enum::name).collect(Collectors.toSet());
        assertThat(allowed).isEqualTo(names);

        // Swagger 허용값은 FE 칩의 순서가 되므로 enum 선언 순서와 같아야 한다
        String[] swaggerValues = ProfileUpdateRequest.class
                .getDeclaredField("category")
                .getAnnotation(Schema.class)
                .allowableValues();
        assertThat(swaggerValues)
                .containsExactly(
                        Arrays.stream(StudyCategory.values()).map(Enum::name).toArray(String[]::new));

        for (StudyCategory category : StudyCategory.values()) {
            assertThat(Categories.label(category.name())).isEqualTo(category.label());
        }
    }

    @Test
    void 리포트_표시_순서는_enum_순서다() {
        List<String> labels = Arrays.stream(StudyCategory.values())
                .map(category -> Categories.label(category.name()))
                .toList();

        assertThat(labels.stream().sorted((a, b) -> Integer.compare(Categories.order(a), Categories.order(b))))
                .containsExactlyElementsOf(labels);
        assertThat(Categories.order("미입력")).isEqualTo(Integer.MAX_VALUE);
    }
}
