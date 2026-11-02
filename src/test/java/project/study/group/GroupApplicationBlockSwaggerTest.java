package project.study.group;

import static org.assertj.core.api.Assertions.assertThat;

import org.assertj.core.api.InstanceOfAssertFactories;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import project.study.TestcontainersConfiguration;

/**
 * 신청·차단·내보내기 Swagger 문서 (BY-1124). FE가 이 설명을 보고 문구를 쓰므로 계약에 적은 말이 빠지지 않았는지 본다.
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
                        "$.paths['/api/groups/{groupId}/applications'].post.description",
                        v -> assertThat(v)
                                .asString()
                                .contains(
                                        "다 찬 그룹에는 신청할 수 없다(GROUP_FULL). 신청한 뒤에 자리가 차면 신청은 기한까지 남고 수락만 GROUP_FULL로 막힌다"))
                .hasPathSatisfying(
                        "$.paths['/api/groups/{groupId}/applications'].post.responses['409'].description",
                        v -> assertThat(v).asString().contains("`GROUP_FULL`"))
                .hasPathSatisfying(
                        "$.paths['/api/groups/{groupId}/members/{userId}'].delete.description",
                        v -> assertThat(v)
                                .asString()
                                .contains("비공개 그룹이면 같은 처리에서 초대코드가 새로 발급되고, 예전 코드·링크로 참여하면 INVITE_CODE_NOT_FOUND다. "
                                        + "응답 형식은 그대로이고 FE는 초대코드 보기(GET /api/groups/{groupId}/invite)를 다시 받아 새 코드를 보여 준다. "
                                        + "내보내진 사람은 새 코드를 받아도 3일 동안 RECENTLY_KICKED다"))
                .hasPathSatisfying(
                        "$.paths['/api/group-applications/{applicationId}/accept'].post.description",
                        v -> assertThat(v).asString().contains("자리가 없으면 GROUP_FULL, 신청은 기한까지 남는다"))
                .hasPathSatisfying(
                        "$.paths['/api/group-applications/{applicationId}/report'].post.description",
                        v -> assertThat(v).asString().contains("신고하면 바로 거절되고 신청한 사람에게는 거절 알림만 간다. 신청한 사람을 차단하지 않는다"))
                .hasPathSatisfying(
                        "$.paths['/api/blocks/{userId}'].delete.description",
                        v -> assertThat(v)
                                .asString()
                                .contains("다음 공부방 입장부터 영상·소리가 다시 오간다")
                                .contains("상대도 나를 차단했다면 계속 오가지 않는다"))
                // 컨트롤러가 늘어도 태그 설명은 GroupController 하나다
                .hasPathSatisfying(
                        "$.tags[?(@.name=='그룹')]", v -> assertThat(v).asList().hasSize(1));
    }

    @Test
    void 그룹_설정_수정_멤버_차단_설명이_실제_동작대로_적힌다() {
        assertThat(mvc.get().uri("/v3/api-docs"))
                .hasStatusOk()
                .bodyJson()
                // 설정 수정은 공개 여부를 받지 않는다. 만들기는 받는다
                .hasPathSatisfying(
                        "$.paths['/api/groups/{groupId}'].patch.responses['400'].description",
                        v -> assertThat(v).asString().contains("없는 시험·목표 값").doesNotContain("공개 여부"))
                .hasPathSatisfying(
                        "$.paths['/api/groups'].post.responses['400'].description",
                        v -> assertThat(v).asString().contains("없는 시험·목표·공개 여부 값"))
                // 자기 자신을 대상으로 한 요청은 BAD_REQUEST다
                .hasPathSatisfying(
                        "$.paths['/api/groups/{groupId}/owner'].put.responses['400'].description",
                        v -> assertThat(v).asString().contains("`BAD_REQUEST` 대상이 자기 자신"))
                .hasPathSatisfying(
                        "$.paths['/api/groups/{groupId}/members/{userId}/report'].post.responses['400'].description",
                        v -> assertThat(v).asString().contains("`BAD_REQUEST` 대상이 자기 자신"))
                // 이미 멤버인지를 가장 먼저 본다
                .hasPathSatisfying(
                        "$.paths['/api/groups/join'].post.description",
                        v -> assertThat(v)
                                .asString()
                                .contains(
                                        "이미 멤버인지를 한도·정원·내보냄 검사보다 먼저 본다 — 이미 멤버면 GROUP_FULL·GROUP_SLOT_LIMIT·RECENTLY_KICKED 대신 joined=false로 200"))
                // 차단은 영상·소리만 막는다 — 멤버 목록·타일에는 그대로 나온다
                .hasPathSatisfying(
                        "$.paths['/api/groups/{groupId}/members/{userId}/report'].post.description",
                        v -> assertThat(v)
                                .asString()
                                .contains("영상·소리가 오가지 않고")
                                .contains("이미 맺은 연결도 닫힌다")
                                .doesNotContain("서로 보이지 않"))
                .hasPathSatisfying(
                        "$.paths['/api/blocks'].get.description",
                        v -> assertThat(v).asString().contains("영상·소리가 오가지 않는다").doesNotContain("서로 보이지 않"))
                // 비공개 그룹에 신청하면 비공개 그룹이 있는지 알려 주지 않는 404다
                .hasPathSatisfying(
                        "$.paths['/api/groups/{groupId}/applications'].post.description",
                        v -> assertThat(v)
                                .asString()
                                .contains("404 `GROUP_NOT_FOUND`")
                                .doesNotContain("400 `BAD_REQUEST`"))
                .hasPathSatisfying(
                        "$.paths['/api/groups/{groupId}/applications'].post.responses['404'].description",
                        v -> assertThat(v)
                                .asString()
                                .contains("`GROUP_NOT_FOUND`")
                                .contains("비공개 그룹"))
                .hasPathSatisfying(
                        "$.paths['/api/groups/{groupId}/applications'].post.responses['400'].description",
                        v -> assertThat(v).asString().doesNotContain("비공개 그룹"));
    }

    /**
     * 403·501은 그룹 오퍼레이션이 @ApiResponse로 직접 적는다. 핸들러를 @ResponseStatus로 두면 springdoc이 그 상태를 모든 오퍼레이션에
     * 일반 응답으로 붙여, 403·501과 무관한 기존 API(랭킹 등)의 문서가 어긋난다.
     */
    @Test
    void 그룹과_무관한_기존_오퍼레이션에_403_501_응답이_자동으로_붙지_않는다() {
        assertThat(mvc.get().uri("/v3/api-docs"))
                .hasStatusOk()
                .bodyJson()
                .hasPathSatisfying(
                        "$.paths['/api/rankings/board'].get.responses",
                        v -> assertThat(v)
                                .asInstanceOf(InstanceOfAssertFactories.MAP)
                                .doesNotContainKeys("403", "501"));
    }
}
