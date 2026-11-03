package project.study.group;

import static org.assertj.core.api.Assertions.assertThat;
import static project.study.group.support.GroupApiTestSupport.assertErrorCode;
import static project.study.group.support.GroupApiTestSupport.assertValidationFailed;
import static project.study.group.support.GroupApiTestSupport.json;
import static project.study.group.support.GroupApiTestSupport.v1Patch;
import static project.study.group.support.GroupApiTestSupport.v1Put;

import java.text.Normalizer;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import project.study.TestcontainersConfiguration;
import project.study.group.support.GroupProbe;
import project.study.group.support.GroupProbe.GroupRow;

/** 그룹 설정 수정(그룹장만, 전체 교체)과 멤버 공부 알림 켜기·끄기 (BY-1124). */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class GroupSettingsApiTest {

    @Autowired
    private MockMvcTester mvc;

    @Autowired
    private JdbcClient jdbc;

    private GroupProbe probe;
    private long owner;
    private long member;
    private long stranger;
    private long groupId;

    @BeforeEach
    void setUp() {
        probe = new GroupProbe(jdbc);
        owner = probe.insertUser();
        member = probe.insertUser();
        stranger = probe.insertUser();
        groupId = probe.insertGroup(owner, "PRIVATE");
        probe.insertMember(groupId, member, "INVITE_CODE");
    }

    private static String update(String name, String category, String descriptionJson) {
        return """
                {"name": "%s", "category": "%s", "description": %s}""".formatted(name, category, descriptionJson);
    }

    private String patchUri() {
        return "/api/groups/" + groupId;
    }

    private String notificationUri() {
        return "/api/groups/" + groupId + "/notifications";
    }

    private static String enabled(boolean value) {
        return "{\"enabled\": " + value + "}";
    }

    @Test
    void 그룹장은_이름_시험_목표_소개를_바꾼다() {
        GroupRow before = probe.group(groupId).orElseThrow();

        assertThat(json(v1Patch(mvc, patchUri(), owner), update("바뀐 이름", "LANGUAGE", "\"바뀐 소개\"")))
                .hasStatus(HttpStatus.NO_CONTENT);

        GroupRow after = probe.group(groupId).orElseThrow();
        assertThat(after.name()).isEqualTo("바뀐 이름");
        assertThat(after.category()).isEqualTo("LANGUAGE");
        assertThat(after.description()).isEqualTo("바뀐 소개");
        // 공개 여부·초대코드·그룹장은 그대로다
        assertThat(after.visibility()).isEqualTo("PRIVATE");
        assertThat(after.inviteCode()).isEqualTo(before.inviteCode());
        assertThat(after.ownerId()).isEqualTo(owner);
        assertThat(after.createdAt()).isEqualTo(before.createdAt());
        assertThat(after.updatedAt()).isAfter(before.updatedAt());
    }

    @Test
    void 전체_교체라_소개를_빼고_보내면_소개가_지워진다() {
        assertThat(json(v1Patch(mvc, patchUri(), owner), update("이름", "ETC", "\"소개\"")))
                .hasStatus(HttpStatus.NO_CONTENT);
        assertThat(probe.group(groupId).orElseThrow().description()).isEqualTo("소개");

        assertThat(json(v1Patch(mvc, patchUri(), owner), """
                        {"name": "이름", "category": "ETC"}""")).hasStatus(HttpStatus.NO_CONTENT);

        assertThat(probe.group(groupId).orElseThrow().description()).isNull();
    }

    @Test
    void 소개가_공백이나_보이지_않는_글자뿐이면_null로_저장한다() {
        for (String description : List.of("\"  \"", "\"\"", "\"\\u3164\"")) {
            assertThat(json(v1Patch(mvc, patchUri(), owner), update("이름", "ETC", description)))
                    .hasStatus(HttpStatus.NO_CONTENT);
            assertThat(probe.group(groupId).orElseThrow().description())
                    .as("소개 %s", description)
                    .isNull();
        }
    }

    @Test
    void 이름은_앞뒤_공백을_떼고_NFC로_저장한다() {
        String nfd = Normalizer.normalize("한글 공부방", Normalizer.Form.NFD);

        assertThat(json(v1Patch(mvc, patchUri(), owner), update("  " + nfd + " ", "ETC", "null")))
                .hasStatus(HttpStatus.NO_CONTENT);

        assertThat(probe.group(groupId).orElseThrow().name()).isEqualTo("한글 공부방");
    }

    @Test
    void 연락처가_있으면_400_CONTACT_NOT_ALLOWED이고_그대로다() {
        GroupRow before = probe.group(groupId).orElseThrow();

        assertErrorCode(
                json(v1Patch(mvc, patchUri(), owner), update("kakao 아이디 abc12", "ETC", "null")),
                HttpStatus.BAD_REQUEST,
                "CONTACT_NOT_ALLOWED");
        assertErrorCode(
                json(v1Patch(mvc, patchUri(), owner), update("이름", "ETC", "\"문의는 a@b.com\"")),
                HttpStatus.BAD_REQUEST,
                "CONTACT_NOT_ALLOWED");

        assertThat(probe.group(groupId).orElseThrow()).isEqualTo(before);
    }

    @Test
    void 이름이_비면_400_VALIDATION_FAILED다() {
        assertValidationFailed(json(v1Patch(mvc, patchUri(), owner), update("  ", "ETC", "null")));
    }

    @Test
    void 그룹장이_아닌_멤버는_403_NOT_GROUP_OWNER다() {
        GroupRow before = probe.group(groupId).orElseThrow();

        assertErrorCode(
                json(v1Patch(mvc, patchUri(), member), update("뺏기", "ETC", "null")),
                HttpStatus.FORBIDDEN,
                "NOT_GROUP_OWNER");

        assertThat(probe.group(groupId).orElseThrow()).isEqualTo(before);
    }

    @Test
    void 멤버가_아니면_403_NOT_GROUP_OWNER다() {
        assertErrorCode(
                json(v1Patch(mvc, patchUri(), stranger), update("뺏기", "ETC", "null")),
                HttpStatus.FORBIDDEN,
                "NOT_GROUP_OWNER");
    }

    @Test
    void 없는_그룹은_404_GROUP_NOT_FOUND다() {
        assertErrorCode(
                json(v1Patch(mvc, "/api/groups/999999999", owner), update("이름", "ETC", "null")),
                HttpStatus.NOT_FOUND,
                "GROUP_NOT_FOUND");
    }

    @Test
    void 지워진_그룹은_그룹장에게도_404_GROUP_NOT_FOUND다() {
        probe.softDeleteGroup(groupId);

        assertErrorCode(
                json(v1Patch(mvc, patchUri(), owner), update("이름", "ETC", "null")),
                HttpStatus.NOT_FOUND,
                "GROUP_NOT_FOUND");
    }

    @Test
    void 알림은_처음엔_꺼져_있고_켜고_끄면_행이_바뀐다() {
        assertThat(notifyOf(member)).isFalse();

        assertThat(json(v1Put(mvc, notificationUri(), member), enabled(true))).hasStatus(HttpStatus.NO_CONTENT);
        assertThat(notifyOf(member)).isTrue();

        assertThat(json(v1Put(mvc, notificationUri(), member), enabled(false))).hasStatus(HttpStatus.NO_CONTENT);
        assertThat(notifyOf(member)).isFalse();
    }

    @Test
    void 같은_값을_다시_보내도_204다() {
        assertThat(json(v1Put(mvc, notificationUri(), member), enabled(true))).hasStatus(HttpStatus.NO_CONTENT);
        assertThat(json(v1Put(mvc, notificationUri(), member), enabled(true))).hasStatus(HttpStatus.NO_CONTENT);
        assertThat(notifyOf(member)).isTrue();

        assertThat(json(v1Put(mvc, notificationUri(), owner), enabled(false))).hasStatus(HttpStatus.NO_CONTENT);
        assertThat(notifyOf(owner)).isFalse();
    }

    @Test
    void 그룹장도_멤버라_알림을_켤_수_있다() {
        assertThat(json(v1Put(mvc, notificationUri(), owner), enabled(true))).hasStatus(HttpStatus.NO_CONTENT);

        assertThat(notifyOf(owner)).isTrue();
    }

    @Test
    void 알림은_내_행과_이_그룹만_바꾼다() {
        long otherGroup = probe.insertGroup(owner, "PUBLIC");
        probe.insertMember(otherGroup, member, "APPLICATION");

        assertThat(json(v1Put(mvc, notificationUri(), member), enabled(true))).hasStatus(HttpStatus.NO_CONTENT);

        assertThat(notifyOf(member)).isTrue();
        assertThat(notifyOf(owner)).as("다른 멤버").isFalse();
        assertThat(probe.members(otherGroup).stream().anyMatch(GroupProbe.MemberRow::notifyEnabled))
                .as("다른 그룹")
                .isFalse();
    }

    @Test
    void 멤버가_아니면_403_NOT_GROUP_MEMBER다() {
        assertErrorCode(
                json(v1Put(mvc, notificationUri(), stranger), enabled(true)), HttpStatus.FORBIDDEN, "NOT_GROUP_MEMBER");
    }

    @Test
    void 나간_멤버는_403_NOT_GROUP_MEMBER다() {
        probe.leaveGroup(groupId, member);

        assertErrorCode(
                json(v1Put(mvc, notificationUri(), member), enabled(true)), HttpStatus.FORBIDDEN, "NOT_GROUP_MEMBER");
    }

    @Test
    void 알림도_없는_그룹은_404_GROUP_NOT_FOUND다() {
        assertErrorCode(
                json(v1Put(mvc, "/api/groups/999999999/notifications", member), enabled(true)),
                HttpStatus.NOT_FOUND,
                "GROUP_NOT_FOUND");
    }

    @Test
    void enabled가_없으면_400_VALIDATION_FAILED다() {
        assertValidationFailed(json(v1Put(mvc, notificationUri(), member), "{}"));
    }

    private boolean notifyOf(long userId) {
        return probe.members(groupId).stream()
                .filter(row -> row.userId() == userId && row.leftAt() == null)
                .findFirst()
                .orElseThrow()
                .notifyEnabled();
    }
}
