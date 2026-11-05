package project.study.group;

import static org.assertj.core.api.Assertions.assertThat;
import static project.study.group.support.GroupApiTestSupport.assertErrorCode;
import static project.study.group.support.GroupApiTestSupport.assertValidationFailed;
import static project.study.group.support.GroupApiTestSupport.json;
import static project.study.group.support.GroupApiTestSupport.v1Post;

import java.text.Normalizer;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.assertj.core.api.InstanceOfAssertFactories;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import project.study.TestcontainersConfiguration;
import project.study.config.ApiVersionConfig;
import project.study.group.support.GroupProbe;
import project.study.group.support.GroupProbe.GroupRow;
import project.study.group.support.GroupProbe.MemberRow;

/** 그룹 만들기 (BY-1124) — 그룹장·멤버 행, 초대코드, 글자 정리, 연락처 거절, 두 한도와 동시 요청. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class GroupCreateApiTest {

    private static final Pattern GROUP_ID = Pattern.compile("\"groupId\"\\s*:\\s*(\\d+)");

    @Autowired
    private MockMvcTester mvc;

    @Autowired
    private JdbcClient jdbc;

    private GroupProbe probe;
    private long user;

    @BeforeEach
    void setUp() {
        probe = new GroupProbe(jdbc);
        user = probe.insertUser();
    }

    private static String body(String name, String visibility, String descriptionJson) {
        return """
                {"name": "%s", "visibility": "%s", "category": "CIVIL_SERVICE", "description": %s}""".formatted(name, visibility, descriptionJson);
    }

    private MvcTestResult create(long userId, String body) {
        return json(v1Post(mvc, "/api/groups", userId), body).exchange();
    }

    private long createdGroupId(MvcTestResult result) throws Exception {
        assertThat(result).hasStatus(HttpStatus.CREATED);
        Matcher matcher = GROUP_ID.matcher(result.getResponse().getContentAsString());
        assertThat(matcher.find()).as("응답에 groupId").isTrue();
        return Long.parseLong(matcher.group(1));
    }

    @Test
    void 공개_그룹을_만들면_만든_사람이_그룹장이자_멤버이고_초대코드는_없다() throws Exception {
        long groupId = createdGroupId(create(user, body("새벽 공시반", "PUBLIC", "\"평일 새벽에 같이 해요\"")));

        GroupRow group = probe.group(groupId).orElseThrow();
        assertThat(group.ownerId()).isEqualTo(user);
        assertThat(group.name()).isEqualTo("새벽 공시반");
        assertThat(group.description()).isEqualTo("평일 새벽에 같이 해요");
        assertThat(group.visibility()).isEqualTo("PUBLIC");
        assertThat(group.category()).isEqualTo("CIVIL_SERVICE");
        assertThat(group.inviteCode()).isNull();
        assertThat(group.deletedAt()).isNull();
        assertThat(group.createdAt()).isNotNull().isEqualTo(group.updatedAt());

        List<MemberRow> members = probe.members(groupId);
        assertThat(members).hasSize(1);
        assertThat(members.getFirst().userId()).isEqualTo(user);
        assertThat(members.getFirst().joinedVia()).isEqualTo("CREATED");
        assertThat(members.getFirst().notifyEnabled()).isFalse();
        assertThat(members.getFirst().leftAt()).isNull();
    }

    @Test
    void 응답은_groupId_하나만_준다() {
        assertThat(create(user, body("새벽 공시반", "PUBLIC", "null")))
                .hasStatus(HttpStatus.CREATED)
                .bodyJson()
                .hasPathSatisfying("$.groupId", v -> assertThat(v).isNotNull())
                .extractingPath("$")
                .asInstanceOf(InstanceOfAssertFactories.MAP)
                .containsOnlyKeys("groupId");
    }

    @Test
    void 비공개_그룹은_6자_초대코드를_받는다() throws Exception {
        long groupId = createdGroupId(create(user, body("우리끼리", "PRIVATE", "null")));

        assertThat(probe.group(groupId).orElseThrow().inviteCode()).matches("[" + InviteCodes.ALPHABET + "]{6}");
    }

    @Test
    void 비공개_그룹마다_초대코드가_다르다() throws Exception {
        long first = createdGroupId(create(user, body("하나", "PRIVATE", "null")));
        long second = createdGroupId(create(user, body("둘", "PRIVATE", "null")));

        assertThat(probe.group(first).orElseThrow().inviteCode())
                .isNotEqualTo(probe.group(second).orElseThrow().inviteCode());
    }

    @Test
    void 이름의_앞뒤_공백은_떼고_NFD는_NFC로_저장한다() throws Exception {
        String nfd = Normalizer.normalize("한글 공부방", Normalizer.Form.NFD);
        assertThat(nfd).isNotEqualTo("한글 공부방");

        long groupId = createdGroupId(create(user, body("  " + nfd + "  ", "PUBLIC", "null")));

        assertThat(probe.group(groupId).orElseThrow().name()).isEqualTo("한글 공부방");
    }

    @Test
    void 소개는_앞뒤_공백을_떼고_저장한다() throws Exception {
        long groupId = createdGroupId(create(user, body("공부방", "PUBLIC", "\"  같이 해요  \"")));

        assertThat(probe.group(groupId).orElseThrow().description()).isEqualTo("같이 해요");
    }

    @Test
    void 소개가_비었거나_공백뿐이거나_보이지_않는_글자뿐이거나_빠졌으면_null로_저장한다() throws Exception {
        for (String description : List.of("\"  \"", "\"\"", "\"\\u3164\\u3164\"", "\"\\u200b \\u00a0\"", "null")) {
            long groupId = createdGroupId(create(user, body("공부방", "PUBLIC", description)));
            assertThat(probe.group(groupId).orElseThrow().description())
                    .as("소개 %s", description)
                    .isNull();
            // 그룹장 한도(2개)에 걸리지 않게 바로 지운다
            probe.softDeleteGroup(groupId);
        }
    }

    @Test
    void 이름에_연락처가_있으면_400_CONTACT_NOT_ALLOWED이고_그룹은_안_생긴다() {
        assertErrorCode(
                json(v1Post(mvc, "/api/groups", user), body("kakao 아이디 abc12", "PUBLIC", "null")),
                HttpStatus.BAD_REQUEST,
                "CONTACT_NOT_ALLOWED");

        assertThat(probe.groupsOwnedBy(user)).isEmpty();
    }

    @Test
    void 소개에_링크가_있으면_400_CONTACT_NOT_ALLOWED이고_그룹은_안_생긴다() {
        assertErrorCode(
                json(v1Post(mvc, "/api/groups", user), body("공부방", "PUBLIC", "\"www.x.com 에서 만나요\"")),
                HttpStatus.BAD_REQUEST,
                "CONTACT_NOT_ALLOWED");

        assertThat(probe.groupsOwnedBy(user)).isEmpty();
    }

    @Test
    void 이름이_보이지_않는_글자뿐이면_400_VALIDATION_FAILED다() {
        assertValidationFailed(json(v1Post(mvc, "/api/groups", user), body("\\u3164", "PUBLIC", "null")));
    }

    @Test
    void 그룹장인_그룹이_둘이어도_세_번째를_만들_수_있다() throws Exception {
        createdGroupId(create(user, body("하나", "PUBLIC", "null")));
        createdGroupId(create(user, body("둘", "PRIVATE", "null")));

        createdGroupId(create(user, body("셋", "PUBLIC", "null")));

        assertThat(probe.groupsOwnedBy(user)).hasSize(3);
    }

    @Test
    void 지워진_그룹은_다섯_개에_세지_않는다() throws Exception {
        long first = createdGroupId(create(user, body("하나", "PUBLIC", "null")));
        for (int i = 0; i < 4; i++) {
            probe.joinNewGroupOf(user);
        }
        probe.softDeleteGroup(first);

        createdGroupId(create(user, body("다섯째", "PUBLIC", "null")));
    }

    @Test
    void 들어간_그룹이_다섯이면_409_GROUP_SLOT_LIMIT이다() {
        for (int i = 0; i < 5; i++) {
            probe.joinNewGroupOf(user);
        }

        assertErrorCode(
                json(v1Post(mvc, "/api/groups", user), body("여섯째", "PUBLIC", "null")),
                HttpStatus.CONFLICT,
                "GROUP_SLOT_LIMIT");

        assertThat(probe.groupsOwnedBy(user)).isEmpty();
    }

    @Test
    void 들어간_그룹과_답을_기다리는_신청이_합쳐_다섯이면_409_GROUP_SLOT_LIMIT이다() {
        probe.joinNewGroupOf(user);
        probe.joinNewGroupOf(user);
        Instant future = Instant.now().plus(2, ChronoUnit.DAYS);
        for (int i = 0; i < 3; i++) {
            probe.insertApplicationToNewGroup(user, "PENDING", future);
        }

        assertErrorCode(
                json(v1Post(mvc, "/api/groups", user), body("여섯째", "PUBLIC", "null")),
                HttpStatus.CONFLICT,
                "GROUP_SLOT_LIMIT");
    }

    @Test
    void 기한이_지난_신청과_끝난_신청_나간_그룹은_다섯_개에_세지_않는다() throws Exception {
        Instant future = Instant.now().plus(2, ChronoUnit.DAYS);
        Instant past = Instant.now().minus(1, ChronoUnit.HOURS);
        probe.joinNewGroupOf(user);
        probe.joinNewGroupOf(user);
        probe.insertApplicationToNewGroup(user, "PENDING", future);
        probe.insertApplicationToNewGroup(user, "PENDING", future);
        // 아래 세 건은 세지 않는다: 기한 지난 PENDING, 이미 답한 신청, 나간 소속
        probe.insertApplicationToNewGroup(user, "PENDING", past);
        probe.insertApplicationToNewGroup(user, "DECLINED", future);
        probe.insertLeftMember(probe.insertGroup(probe.insertUser(), "PUBLIC"), user, "LEFT", past);

        createdGroupId(create(user, body("다섯째", "PUBLIC", "null")));
    }

    @Test
    void 그룹장인_그룹만으로_다섯이면_409_GROUP_SLOT_LIMIT이다() {
        for (int i = 0; i < 5; i++) {
            probe.insertGroup(user, "PUBLIC");
        }

        assertErrorCode(
                json(v1Post(mvc, "/api/groups", user), body("여섯째", "PUBLIC", "null")),
                HttpStatus.CONFLICT,
                "GROUP_SLOT_LIMIT");
    }

    @Test
    void 같은_사용자가_동시에_여섯_번_만들어도_그룹은_다섯이다() throws Exception {
        int threads = 6;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        try {
            List<Callable<Integer>> tasks = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                String name = "동시" + i;
                tasks.add(() -> {
                    ready.countDown();
                    go.await();
                    return create(user, body(name, "PRIVATE", "null"))
                            .getResponse()
                            .getStatus();
                });
            }
            List<Future<Integer>> futures = new ArrayList<>();
            for (Callable<Integer> task : tasks) {
                futures.add(pool.submit(task));
            }
            ready.await();
            go.countDown();
            List<Integer> statuses = new ArrayList<>();
            for (Future<Integer> future : futures) {
                statuses.add(future.get());
            }

            assertThat(statuses).containsExactlyInAnyOrder(201, 201, 201, 201, 201, 409);
        } finally {
            pool.shutdownNow();
        }

        List<GroupRow> owned = probe.groupsOwnedBy(user);
        assertThat(owned).hasSize(5);
        for (GroupRow group : owned) {
            assertThat(probe.members(group.id())).hasSize(1);
        }
    }

    @Test
    void 인증_없으면_401이다() {
        assertThat(mvc.post()
                        .uri("/api/groups")
                        .header(ApiVersionConfig.HEADER, "1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("공부방", "PUBLIC", "null")))
                .hasStatus(HttpStatus.UNAUTHORIZED);
    }
}
