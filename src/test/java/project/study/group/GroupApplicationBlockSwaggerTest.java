package project.study.group;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import project.study.TestcontainersConfiguration;

/**
 * 신청·차단 Swagger 문서 (BY-1124). FE가 이 설명을 보고 문구를 쓰므로 계약에 적은 말이 빠지지 않았는지 본다.
 * 경로·상태 코드 계약은 {@link GroupContractApiTest}에 있다 (그 파일이 checkstyle 파일 길이 한도에 닿아 따로 뒀다).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class GroupApplicationBlockSwaggerTest {

    @Autowired
    private MockMvcTester mvc;

    @Test
    void Swagger에_신청_차단_오퍼레이션과_설명이_실린다() {
        assertThat(mvc.get().uri("/v3/api-docs"))
                .hasStatusOk()
                .bodyJson()
                // 신청은 클래스 경로가 없고 차단은 다른 패키지라, 둘 다 버전 1이 실리는지 따로 본다
                .hasPathSatisfying(
                        "$.paths['/api/groups/{groupId}/applications'].post.parameters[?(@.name=='API-Version')].schema.default",
                        v -> assertThat(v).asList().containsExactly("1"))
                .hasPathSatisfying(
                        "$.paths['/api/blocks'].get.parameters[?(@.name=='API-Version')].schema.default",
                        v -> assertThat(v).asList().containsExactly("1"))
                .hasPathSatisfying(
                        "$.paths['/api/groups/{groupId}/applications'].post.description",
                        v -> assertThat(v).asString().contains("공개 그룹만. 답이 없으면 3일 뒤 취소"))
                .hasPathSatisfying(
                        "$.paths['/api/group-applications/{applicationId}/accept'].post.description",
                        v -> assertThat(v).asString().contains("자리가 없으면 GROUP_FULL, 신청은 기한까지 남는다"))
                .hasPathSatisfying(
                        "$.paths['/api/group-applications/{applicationId}/report'].post.description",
                        v -> assertThat(v).asString().contains("신고하면 바로 거절되고 신청한 사람에게는 거절 알림만 간다. 신청한 사람을 차단하지 않는다"))
                .hasPathSatisfying(
                        "$.paths['/api/blocks/{userId}'].delete.description",
                        v -> assertThat(v).asString().contains("다음 공부방 입장부터 다시 보인다"))
                // 컨트롤러가 늘어도 태그 설명은 GroupController 하나다
                .hasPathSatisfying(
                        "$.tags[?(@.name=='그룹')]", v -> assertThat(v).asList().hasSize(1));
    }
}
