package project.study.studysession;

import static org.assertj.core.api.Assertions.assertThat;
import static project.study.group.support.GroupDeskTestConfig.NOW;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Collectors;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import project.study.TestcontainersConfiguration;
import project.study.group.support.GroupDeskTestConfig;
import project.study.group.support.GroupProbe;
import project.study.group.support.StudyFixture;
import project.study.push.service.PushSender;
import project.study.studysession.dto.NewDraft;
import project.study.studysession.repository.ActiveStudySessionBatchRepository;
import project.study.studysession.repository.ActiveStudySessionBatchRepository.SnapshotRow;
import project.study.studysession.repository.ActiveStudySessionRepository;
import project.study.studysession.service.NewDraftSource;

/**
 * 새 draft 꺼내기와 {@code start_notified} 열 (BY-1124, V38) — 한 번만, 한 태스크만 가져가고, 하트비트 UPSERT 경로는 이 열을 건드리지 않는다.
 * 컨텍스트를 공유하려고 {@link PushSender} 목을 선언해 둔다(그룹 시작 알림 테스트와 같은 키).
 */
@SpringBootTest
@Import({TestcontainersConfiguration.class, GroupDeskTestConfig.class})
class NewDraftClaimTest {

    private static final Instant START = NOW.minusSeconds(30);

    @Autowired
    private NewDraftSource source;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private ActiveStudySessionBatchRepository batchRepository;

    @Autowired
    private ActiveStudySessionRepository repository;

    @MockitoBean
    private PushSender pushSender;

    private GroupProbe probe;
    private StudyFixture study;
    private final List<Long> users = new ArrayList<>();

    @BeforeEach
    void setUp() {
        jdbc.sql("update active_study_session set start_notified = true where not start_notified")
                .update();
        probe = new GroupProbe(jdbc);
        study = new StudyFixture(jdbc);
    }

    @AfterEach
    void tearDown() {
        study.clear(users);
    }

    private long user() {
        long id = probe.insertUser();
        users.add(id);
        return id;
    }

    private boolean startNotified(long userId, Instant startedAt) {
        return jdbc.sql("select start_notified from active_study_session where user_id = :u and started_at = :s")
                .param("u", userId)
                .param("s", Timestamp.from(startedAt))
                .query(Boolean.class)
                .single();
    }

    private SnapshotRow snapshot(long userId, Instant startedAt, Instant reportedAt) {
        return new SnapshotRow(userId, startedAt, reportedAt, reportedAt, 30, 25, "[]", "[]");
    }

    @Test
    void 새_draft를_꺼내면_표시되어_다음에는_나오지_않는다() {
        long a = user();
        long b = user();
        study.draft(a, START, NOW, 25, "[]");
        study.draft(b, START, NOW, 25, "[]");

        List<NewDraft> first = source.claimNewDrafts(NOW);
        List<NewDraft> second = source.claimNewDrafts(NOW.plusSeconds(10));

        assertThat(first).containsExactlyInAnyOrder(new NewDraft(a, START), new NewDraft(b, START));
        assertThat(second).isEmpty();
        assertThat(startNotified(a, START)).isTrue();
    }

    @Test
    void 시작한_지_10분이_넘은_draft와_이미_확정된_draft는_표시만_하고_돌려주지_않는다() {
        long late = user();
        long finalized = user();
        long fresh = user();
        Instant lateStart = NOW.minus(Duration.ofMinutes(10)).minusSeconds(1);
        study.draft(late, lateStart, NOW, 25, "[]");
        study.session(finalized, START, 600);
        study.draft(finalized, START, NOW, 25, "[]");
        // 경계: 정확히 10분 전에 시작한 draft는 아직 새 시작이다
        Instant edgeStart = NOW.minus(Duration.ofMinutes(10));
        study.draft(fresh, edgeStart, NOW, 25, "[]");

        List<NewDraft> claimed = source.claimNewDrafts(NOW);

        assertThat(claimed).containsExactly(new NewDraft(fresh, edgeStart));
        assertThat(startNotified(late, lateStart)).isTrue();
        assertThat(startNotified(finalized, START)).isTrue();
    }

    @Test
    void 여러_태스크가_동시에_꺼내도_각_draft는_한_번만_나온다() throws Exception {
        int draftCount = 30;
        for (int i = 0; i < draftCount; i++) {
            study.draft(user(), START, NOW, 25, "[]");
        }
        int tasks = 4;
        CountDownLatch go = new CountDownLatch(1);
        try (ExecutorService pool = Executors.newFixedThreadPool(tasks)) {
            List<Future<List<NewDraft>>> results = new ArrayList<>();
            for (int i = 0; i < tasks; i++) {
                results.add(pool.submit(() -> {
                    go.await();
                    return source.claimNewDrafts(NOW);
                }));
            }
            go.countDown();
            List<NewDraft> all = new ArrayList<>();
            for (Future<List<NewDraft>> result : results) {
                all.addAll(result.get());
            }

            assertThat(all).hasSize(draftCount);
            assertThat(all.stream().map(NewDraft::userId).collect(Collectors.toSet()))
                    .hasSize(draftCount);
        }
    }

    @Test
    void 하트비트_벌크_UPSERT로_처음_들어온_행은_아직_알리지_않은_상태다() {
        long id = user();

        batchRepository.bulkUpsert(List.of(snapshot(id, START, NOW)));

        assertThat(startNotified(id, START)).isFalse();
    }

    @Test
    void 하트비트_단건_UPSERT로_처음_들어온_행도_아직_알리지_않은_상태다() {
        long id = user();

        repository.upsertSnapshot(id, START, NOW, NOW, 30, 25, "[]");

        assertThat(startNotified(id, START)).isFalse();
    }

    @Test
    void 이미_꺼낸_행에_하트비트가_다시_와도_알린_상태가_유지된다() {
        long id = user();
        batchRepository.bulkUpsert(List.of(snapshot(id, START, NOW)));
        assertThat(source.claimNewDrafts(NOW)).containsExactly(new NewDraft(id, START));

        batchRepository.bulkUpsert(List.of(snapshot(id, START, NOW.plusSeconds(30))));
        repository.upsertSnapshot(id, START, NOW.plusSeconds(60), NOW.plusSeconds(60), 90, 80, "[]");

        assertThat(startNotified(id, START)).isTrue();
        assertThat(source.claimNewDrafts(NOW.plusSeconds(60))).isEmpty();
    }

    /**
     * V38의 "기존 행은 알리지 않는다"를 실제 마이그레이션으로 확인한다. 같은 DB의 새 스키마에 V37까지 적용해 draft를 넣고 V38을 적용한 뒤,
     * 그 행은 true이고 V38 뒤에 들어오는 행은 기본값 false인지 본다. 운영에서 배포 시점에 이미 있던 draft가 한꺼번에 알림이 되는 일을 막는 부분이다.
     */
    @Test
    void V38은_배포_전에_있던_행을_알린_것으로_채우고_새_행은_기본값_false로_둔다() {
        String schema = "start_alert_v38_check";
        jdbc.sql("DROP SCHEMA IF EXISTS " + schema + " CASCADE").update();
        try {
            Flyway.configure()
                    .dataSource(dataSource)
                    .schemas(schema)
                    .defaultSchema(schema)
                    .createSchemas(true)
                    .locations("classpath:db/migration")
                    .target("37")
                    .load()
                    .migrate();
            jdbc.sql("insert into " + schema + ".users (provider, provider_user_id, created_at, updated_at) "
                            + "values ('DEVICE', 'v38-check', now(), now())")
                    .update();
            jdbc.sql("insert into " + schema + ".active_study_session "
                            + "(user_id, started_at, reported_at, last_seen_at, study_sec, focus_sec) "
                            + "select id, now(), now(), now(), 30, 25 from " + schema + ".users")
                    .update();

            Flyway.configure()
                    .dataSource(dataSource)
                    .schemas(schema)
                    .defaultSchema(schema)
                    .locations("classpath:db/migration")
                    .target("38")
                    .load()
                    .migrate();
            jdbc.sql("insert into " + schema + ".active_study_session "
                            + "(user_id, started_at, reported_at, last_seen_at, study_sec, focus_sec) "
                            + "select id, now() + interval '1 minute', now(), now(), 30, 25 from " + schema + ".users")
                    .update();

            List<Boolean> flags = jdbc.sql(
                            "select start_notified from " + schema + ".active_study_session order by started_at")
                    .query(Boolean.class)
                    .list();
            assertThat(flags).containsExactly(true, false);
        } finally {
            jdbc.sql("DROP SCHEMA IF EXISTS " + schema + " CASCADE").update();
        }
    }
}
