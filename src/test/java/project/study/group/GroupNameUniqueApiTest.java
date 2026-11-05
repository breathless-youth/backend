package project.study.group;

import static org.assertj.core.api.Assertions.assertThat;
import static project.study.group.support.GroupApiTestSupport.assertErrorCode;
import static project.study.group.support.GroupApiTestSupport.json;
import static project.study.group.support.GroupApiTestSupport.v1Post;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import project.study.TestcontainersConfiguration;
import project.study.group.support.GroupProbe;

/** 그룹 만들기 (BY-1124) — 그룹장·멤버 행, 초대코드, 글자 정리, 연락처 거절, 두 한도와 동시 요청. */
/** 공개 그룹 이름은 겹칠 수 없다 (BY-1124). 비공개 그룹과 지워진 그룹은 세지 않는다. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class GroupNameUniqueApiTest {

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
        probe.softDeleteAllPublicGroups();
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
    void 공개_그룹_이름이_이미_있으면_409_GROUP_NAME_TAKEN이고_그룹은_안_생긴다() throws Exception {
        long other = probe.insertUser();
        createdGroupId(create(other, body("새벽 공시반", "PUBLIC", "null")));

        assertErrorCode(
                json(v1Post(mvc, "/api/groups", user), body("새벽 공시반", "PUBLIC", "null")),
                HttpStatus.CONFLICT,
                "GROUP_NAME_TAKEN");

        assertThat(probe.groupsOwnedBy(user)).isEmpty();
    }

    @Test
    void 같은_사용자도_같은_공개_이름으로_두_번_만들_수_없다() throws Exception {
        createdGroupId(create(user, body("새벽 공시반", "PUBLIC", "null")));

        assertErrorCode(
                json(v1Post(mvc, "/api/groups", user), body("새벽 공시반", "PUBLIC", "null")),
                HttpStatus.CONFLICT,
                "GROUP_NAME_TAKEN");
    }

    @Test
    void 공백과_대소문자만_다른_이름도_같은_이름이다() throws Exception {
        createdGroupId(create(probe.insertUser(), body("Study Room", "PUBLIC", "null")));

        for (String name : List.of("study room", "STUDY ROOM", "Study  Room", " StudyRoom ")) {
            assertErrorCode(
                    json(v1Post(mvc, "/api/groups", user), body(name, "PUBLIC", "null")),
                    HttpStatus.CONFLICT,
                    "GROUP_NAME_TAKEN");
        }
    }

    @Test
    void 비공개_그룹은_같은_이름을_쓸_수_있고_공개_이름도_가져갈_수_있다() throws Exception {
        long other = probe.insertUser();
        createdGroupId(create(other, body("우리끼리", "PRIVATE", "null")));
        createdGroupId(create(other, body("새벽 공시반", "PUBLIC", "null")));

        createdGroupId(create(user, body("우리끼리", "PRIVATE", "null")));
        createdGroupId(create(user, body("새벽 공시반", "PRIVATE", "null")));
        createdGroupId(create(user, body("우리끼리", "PUBLIC", "null")));
    }

    @Test
    void 지워진_공개_그룹의_이름은_다시_쓸_수_있다() throws Exception {
        long first = createdGroupId(create(probe.insertUser(), body("새벽 공시반", "PUBLIC", "null")));
        probe.softDeleteGroup(first);

        createdGroupId(create(user, body("새벽 공시반", "PUBLIC", "null")));
    }

    @Test
    void 이름이_겹치고_한도도_걸리면_GROUP_SLOT_LIMIT이_먼저다() throws Exception {
        createdGroupId(create(probe.insertUser(), body("새벽 공시반", "PUBLIC", "null")));
        for (int i = 0; i < 5; i++) {
            probe.joinNewGroupOf(user);
        }

        assertErrorCode(
                json(v1Post(mvc, "/api/groups", user), body("새벽 공시반", "PUBLIC", "null")),
                HttpStatus.CONFLICT,
                "GROUP_SLOT_LIMIT");
    }

    @Test
    void 서로_다른_사용자가_같은_공개_이름으로_동시에_만들어도_하나만_생긴다() throws Exception {
        int threads = 6;
        List<Long> users = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            users.add(probe.insertUser());
        }
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        List<Integer> statuses = new ArrayList<>();
        try {
            List<Future<Integer>> futures = new ArrayList<>();
            for (long userId : users) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    return create(userId, body("동시 이름", "PUBLIC", "null"))
                            .getResponse()
                            .getStatus();
                }));
            }
            ready.await();
            go.countDown();
            for (Future<Integer> future : futures) {
                statuses.add(future.get());
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(statuses).containsExactlyInAnyOrder(201, 409, 409, 409, 409, 409);
    }
}
