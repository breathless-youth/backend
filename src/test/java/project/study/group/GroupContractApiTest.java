package project.study.group;

import static org.assertj.core.api.Assertions.assertThat;
import static project.study.group.support.GroupApiTestSupport.assertErrorCode;
import static project.study.group.support.GroupApiTestSupport.assertNotImplemented;
import static project.study.group.support.GroupApiTestSupport.assertValidationFailed;
import static project.study.group.support.GroupApiTestSupport.json;
import static project.study.group.support.GroupApiTestSupport.v1Delete;
import static project.study.group.support.GroupApiTestSupport.v1Get;
import static project.study.group.support.GroupApiTestSupport.v1Post;
import static project.study.group.support.GroupApiTestSupport.v1Put;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.assertj.core.api.InstanceOfAssertFactories;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import project.study.TestcontainersConfiguration;
import project.study.config.ApiVersionConfig;

/**
 * 그룹 REST 계약 (BY-1124). 로직 전에는 컨트롤러가 501 {@code NOT_IMPLEMENTED}를 주고, FE는 Swagger의 경로·DTO로 타입을 쓴다.
 * 501을 단언하는 엔드포인트는 서비스가 DB를 건드리지 않으므로 {@code asUser}가 가리키는 사용자 행이 필요 없다. 로직이 채워진
 * 엔드포인트(그룹 만들기·내 그룹·그룹 홈·설정·알림)는 각자의 API 테스트가 실제 사용자 행으로 본다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class GroupContractApiTest {

    private static final String JOIN_BODY = """
            {"inviteCode": "K7M2PQ", "via": "LINK"}""";

    private static final String APPLY_BODY = """
            {"introduction": "새벽 5시에 일어나는 공시생이에요"}""";

    private static final long USER = 1L;

    private static final Set<String> HTTP_METHODS = Set.of("get", "put", "post", "delete", "patch");

    @Autowired
    private MockMvcTester mvc;

    @Test
    void 로직_전에는_501과_NOT_IMPLEMENTED다() {
        // 만들기·내 그룹·그룹 홈·설정 수정·알림 켜기는 로직이 채워졌다 — GroupCreateApiTest·MyGroupsApiTest·GroupDeskApiTest·
        // GroupSettingsApiTest가 본다
        assertNotImplemented(v1Get(mvc, "/api/groups/public", USER));
        assertNotImplemented(v1Get(mvc, "/api/groups/public/10", USER));
    }

    @Test
    void 고정_경로가_groupId_경로보다_먼저_잡힌다() {
        // /{groupId}가 먼저 잡혔다면 "me"·"public"을 Long으로 바꾸다 400이 난다
        assertThat(v1Get(mvc, "/api/groups/me", USER)).hasStatusOk();
        assertNotImplemented(v1Get(mvc, "/api/groups/public", USER));
    }

    @Test
    void 인증_없으면_401이다() {
        assertThat(mvc.get().uri("/api/groups/me").header(ApiVersionConfig.HEADER, "1"))
                .hasStatus(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void 이름이_21자면_400_VALIDATION_FAILED다() {
        String body = """
                {"name": "%s", "visibility": "PUBLIC", "category": "CIVIL_SERVICE"}""".formatted("가".repeat(21));

        assertValidationFailed(json(v1Post(mvc, "/api/groups", USER), body));
    }

    @Test
    void 없는_시험_목표는_400이다() {
        String body = """
                {"name": "새벽 공시반", "visibility": "PUBLIC", "category": "UNKNOWN"}""";

        assertThat(json(v1Post(mvc, "/api/groups", USER), body)).hasStatus(HttpStatus.BAD_REQUEST);
    }

    @Test
    void size가_50을_넘으면_400이다() {
        assertThat(v1Get(mvc, "/api/groups/public?size=51", USER)).hasStatus(HttpStatus.BAD_REQUEST);
    }

    @Test
    void Swagger에_그룹_태그와_버전_1이_실린다() {
        assertThat(mvc.get().uri("/v3/api-docs"))
                .hasStatusOk()
                .bodyJson()
                .hasPathSatisfying(
                        "$.paths['/api/groups/me'].get.tags[0]",
                        v -> assertThat(v).isEqualTo("그룹"))
                .hasPathSatisfying(
                        "$.paths['/api/groups/me'].get.parameters[?(@.name=='API-Version')].schema.default",
                        v -> assertThat(v).asList().containsExactly("1"))
                // 7개 오퍼레이션이 모두 문서에 실린다
                .hasPath("$.paths['/api/groups'].post")
                .hasPath("$.paths['/api/groups/{groupId}'].get")
                .hasPath("$.paths['/api/groups/{groupId}'].patch")
                .hasPath("$.paths['/api/groups/public'].get")
                .hasPath("$.paths['/api/groups/public/{groupId}'].get")
                .hasPath("$.paths['/api/groups/{groupId}/notifications'].put");
    }

    @Test
    void 멤버_초대_공부방_경로도_로직_전에는_501이다() {
        assertNotImplemented(v1Get(mvc, "/api/groups/10/members", USER));
        assertNotImplemented(json(v1Put(mvc, "/api/groups/10/owner", USER), """
                {"userId": 2}"""));
        assertNotImplemented(v1Delete(mvc, "/api/groups/10/members/2", USER));
        assertNotImplemented(v1Post(mvc, "/api/groups/10/leave", USER));
        assertNotImplemented(v1Get(mvc, "/api/groups/10/invite", USER));
        assertNotImplemented(v1Post(mvc, "/api/groups/10/invite", USER));
        assertNotImplemented(json(v1Post(mvc, "/api/groups/join", USER), JOIN_BODY));
        assertNotImplemented(v1Post(mvc, "/api/groups/10/room/join", USER));
        assertNotImplemented(v1Post(mvc, "/api/groups/10/room/leave", USER));
        assertNotImplemented(json(v1Post(mvc, "/api/groups/10/members/2/report", USER), """
                {"reason": "욕설을 했어요"}"""));
    }

    @Test
    void join은_고정_경로로_잡힌다() {
        // POST /{groupId}/...로 잡혔다면 "join"을 Long으로 바꾸다 400이 나거나 405가 난다
        assertNotImplemented(json(v1Post(mvc, "/api/groups/join", USER), JOIN_BODY));
    }

    @Test
    void 신고_사유가_101자면_400_VALIDATION_FAILED다() {
        String body = """
                {"reason": "%s"}""".formatted("가".repeat(101));

        assertValidationFailed(json(v1Post(mvc, "/api/groups/1/members/2/report", USER), body));
    }

    @Test
    void 신고_사유는_비워도_된다() {
        assertNotImplemented(json(v1Post(mvc, "/api/groups/1/members/2/report", USER), "{}"));
    }

    @Test
    void 초대코드가_비면_400_VALIDATION_FAILED다() {
        assertValidationFailed(json(v1Post(mvc, "/api/groups/join", USER), """
                        {"inviteCode": " ", "via": "CODE"}"""));
    }

    @Test
    void 초대코드는_형식을_검사하지_않는다() {
        // 틀린 형식도 3단계에서 404 INVITE_CODE_NOT_FOUND가 되므로 여기서는 400이 아니다
        assertNotImplemented(json(v1Post(mvc, "/api/groups/join", USER), """
                {"inviteCode": "12", "via": "CODE"}"""));
    }

    @Test
    void 없는_들어온_경로는_400_BAD_REQUEST다() {
        assertErrorCode(json(v1Post(mvc, "/api/groups/join", USER), """
                {"inviteCode": "K7M2PQ", "via": "QR"}"""), HttpStatus.BAD_REQUEST, "BAD_REQUEST");
    }

    @Test
    void 그룹장을_넘길_대상이_없으면_400_VALIDATION_FAILED다() {
        assertValidationFailed(json(v1Put(mvc, "/api/groups/10/owner", USER), "{}"));
    }

    @Test
    void Swagger에_멤버_초대_공부방_오퍼레이션과_설명이_실린다() {
        assertThat(mvc.get().uri("/v3/api-docs"))
                .hasStatusOk()
                .bodyJson()
                .hasPathSatisfying(
                        "$.paths['/api/groups/{groupId}/members'].get.tags[0]",
                        v -> assertThat(v).isEqualTo("그룹"))
                .hasPathSatisfying(
                        "$.paths['/api/groups/{groupId}/members'].get.parameters[?(@.name=='API-Version')].schema.default",
                        v -> assertThat(v).asList().containsExactly("1"))
                .hasPath("$.paths['/api/groups/{groupId}/owner'].put")
                .hasPath("$.paths['/api/groups/{groupId}/members/{userId}'].delete")
                .hasPath("$.paths['/api/groups/{groupId}/leave'].post")
                .hasPath("$.paths['/api/groups/{groupId}/invite'].get")
                .hasPath("$.paths['/api/groups/{groupId}/invite'].post")
                .hasPath("$.paths['/api/groups/join'].post")
                .hasPath("$.paths['/api/groups/{groupId}/room/join'].post")
                .hasPath("$.paths['/api/groups/{groupId}/room/leave'].post")
                .hasPath("$.paths['/api/groups/{groupId}/members/{userId}/report'].post")
                // FE가 이 설명을 보고 타입·문구를 쓴다
                .hasPathSatisfying(
                        "$.components.schemas.GroupInviteResponse.properties.inviteCode.description",
                        v -> assertThat(v).asString().contains("영문 대문자·숫자 6자리(0·O·1·I·L 제외), 대소문자 무시"))
                .hasPathSatisfying(
                        "$.components.schemas.GroupJoinRequest.properties.inviteCode.description",
                        v -> assertThat(v).asString().contains("영문 대문자·숫자 6자리(0·O·1·I·L 제외), 대소문자 무시"))
                .hasPathSatisfying(
                        "$.paths['/api/groups/join'].post.description",
                        v -> assertThat(v).asString().contains("이미 멤버면 409 대신 joined=false로 200"))
                .hasPathSatisfying(
                        "$.paths['/api/groups/{groupId}/room/join'].post.description",
                        v -> assertThat(v)
                                .asString()
                                .contains("/topic/room/{roomId}를 구독한다")
                                .contains("/docs/websocket.html"))
                .hasPathSatisfying(
                        "$.paths['/api/groups/{groupId}/members/{userId}/report'].post.description",
                        v -> assertThat(v).asString().contains("신고하면 차단도 된다"))
                // 태그 설명은 GroupController에만 있다 — 컨트롤러마다 적으면 springdoc이 합치다 어긋난다
                .hasPathSatisfying(
                        "$.tags[?(@.name=='그룹')]", v -> assertThat(v).asList().hasSize(1));
    }

    @Test
    void 신청과_차단_경로도_로직_전에는_501이다() {
        assertNotImplemented(json(v1Post(mvc, "/api/groups/10/applications", USER), APPLY_BODY));
        assertNotImplemented(v1Get(mvc, "/api/groups/10/applications", USER));
        assertNotImplemented(v1Get(mvc, "/api/group-applications/me", USER));
        assertNotImplemented(v1Delete(mvc, "/api/group-applications/55", USER));
        assertNotImplemented(v1Post(mvc, "/api/group-applications/55/accept", USER));
        assertNotImplemented(v1Post(mvc, "/api/group-applications/55/decline", USER));
        assertNotImplemented(json(v1Post(mvc, "/api/group-applications/55/report", USER), """
                {"reason": "부적절한 소개 글이에요"}"""));
        assertNotImplemented(v1Get(mvc, "/api/blocks", USER));
        assertNotImplemented(v1Delete(mvc, "/api/blocks/2", USER));
    }

    @Test
    void 신청_소개_글이_비었거나_51자면_400이다() {
        assertValidationFailed(json(v1Post(mvc, "/api/groups/10/applications", USER), """
                {"introduction": "  "}"""));
        assertValidationFailed(json(v1Post(mvc, "/api/groups/10/applications", USER), """
                {"introduction": "%s"}""".formatted("가".repeat(51))));
        assertValidationFailed(json(v1Post(mvc, "/api/groups/10/applications", USER), "{}"));
        // 경계: 50자는 검증을 통과해 501까지 간다
        assertNotImplemented(json(v1Post(mvc, "/api/groups/10/applications", USER), """
                {"introduction": "%s"}""".formatted("가".repeat(50))));
    }

    @Test
    void 신청_신고_사유가_101자면_400_VALIDATION_FAILED다() {
        assertValidationFailed(
                json(v1Post(mvc, "/api/group-applications/55/report", USER), """
                {"reason": "%s"}""".formatted("가".repeat(101))));
    }

    @Test
    void 그룹_API는_모두_그룹_태그_하나다() {
        assertThat(mvc.get().uri("/v3/api-docs"))
                .hasStatusOk()
                .bodyJson()
                .extractingPath("$.paths")
                .asInstanceOf(InstanceOfAssertFactories.map(String.class, Object.class))
                .satisfies(pathItems -> {
                    List<String> groupPaths = pathItems.keySet().stream()
                            .filter(path -> path.startsWith("/api/groups")
                                    || path.startsWith("/api/group-applications")
                                    || path.startsWith("/api/blocks"))
                            .toList();
                    // 신청·차단 경로가 문서에 실려야 아래 검사가 의미 있다
                    assertThat(groupPaths)
                            .contains(
                                    "/api/groups/{groupId}/applications",
                                    "/api/group-applications/me",
                                    "/api/group-applications/{applicationId}",
                                    "/api/group-applications/{applicationId}/accept",
                                    "/api/group-applications/{applicationId}/decline",
                                    "/api/group-applications/{applicationId}/report",
                                    "/api/blocks",
                                    "/api/blocks/{userId}");
                    for (String path : groupPaths) {
                        castMap(pathItems.get(path)).forEach((method, operation) -> {
                            if (HTTP_METHODS.contains(method)) {
                                assertThat(castMap(operation).get("tags"))
                                        .as("%s %s", method, path)
                                        .isEqualTo(List.of("그룹"));
                            }
                        });
                    }
                });
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Object value) {
        return (Map<String, Object>) value;
    }
}
