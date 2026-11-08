package project.study.group.translation;

import static org.assertj.core.api.Assertions.assertThat;
import static project.study.group.support.GroupApiTestSupport.race;
import static project.study.group.translation.TranslationField.*;
import static project.study.group.translation.TranslationLanguage.*;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.Callable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import project.study.TestcontainersConfiguration;
import project.study.group.support.GroupProbe;
import project.study.room.support.MutableClock;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class TranslationCacheRepositoryTest {
    @Autowired
    JdbcClient jdbc;

    @Autowired
    javax.sql.DataSource dataSource;

    @Autowired
    PlatformTransactionManager manager;

    final MutableClock clock = MutableClock.at(Instant.parse("2026-11-08T10:00:00Z"));
    TranslationCacheRepository cache;
    long owner;
    long group;
    TranslationText name;

    @BeforeEach
    void setup() {
        cache = new TranslationCacheRepository(jdbc, manager, clock);
        GroupProbe probe = new GroupProbe(jdbc);
        owner = probe.insertUser();
        group = probe.insertGroup(owner, "PUBLIC", "새벽 공부", "ETC");
        probe.setDescription(group, "함께 공부");
        name = new TranslationText(group, NAME, "새벽 공부");
    }

    @AfterEach
    void cleanup() {
        jdbc.sql("delete from study_group_member where group_id = :id")
                .param("id", group)
                .update();
        jdbc.sql("delete from study_group where id = :id").param("id", group).update();
        jdbc.sql("delete from users where id = :id").param("id", owner).update();
    }

    @Test
    void simultaneousRequestsClaimExactlyOneLease() throws Exception {
        TranslationCacheRepository other = new TranslationCacheRepository(jdbc, manager, clock);
        List<Callable<Integer>> tasks = List.of(
                () -> cache.claim(List.of(name), EN).size(),
                () -> other.claim(List.of(name), EN).size());
        assertThat(race(tasks).stream().mapToInt(Integer::intValue).sum()).isEqualTo(1);
        assertThat(other.claim(List.of(name), JA)).hasSize(1);
    }

    @Test
    void successfulResultsAreSharedAndOnlyChangedFieldIsInvalidated() {
        TranslationText description = new TranslationText(group, DESCRIPTION, "함께 공부");
        for (var lease : cache.claim(List.of(name, description), EN)) {
            assertThat(cache.complete(lease, "translated", TranslationProvider.GOOGLE))
                    .isTrue();
        }
        assertThat(new TranslationCacheRepository(jdbc, manager, clock).find(List.of(name, description), EN))
                .hasSize(2);
        jdbc.sql("update study_group set description = '변경' where id = :id")
                .param("id", group)
                .update();
        TranslationText changed = new TranslationText(group, DESCRIPTION, "변경");
        assertThat(cache.find(List.of(name, changed), EN)).hasSize(1);
        assertThat(cache.claim(List.of(name, changed), EN))
                .singleElement()
                .satisfies(l -> assertThat(l.source()).isEqualTo(changed));
    }

    @Test
    void staleSourceOrLeaseCannotOverwriteNewerWork() {
        var old = cache.claim(List.of(name), EN).getFirst();
        jdbc.sql("update study_group set name = '변경' where id = :id")
                .param("id", group)
                .update();
        assertThat(cache.claim(List.of(name), JA)).isEmpty();
        assertThat(cache.complete(old, "old", TranslationProvider.GOOGLE)).isFalse();
        var changed = new TranslationText(group, NAME, "변경");
        var next = cache.claim(List.of(changed), EN).getFirst();
        clock.advance(Duration.ofSeconds(11));
        var reclaimed = cache.claim(List.of(changed), EN).getFirst();
        assertThat(cache.complete(next, "expired", TranslationProvider.AZURE)).isFalse();
        assertThat(cache.complete(reclaimed, "new", TranslationProvider.AZURE)).isTrue();
        assertThat(cache.find(List.of(changed), EN).values())
                .singleElement()
                .satisfies(v -> assertThat(v.text()).isEqualTo("new"));
    }

    @Test
    void failureCooldownDoesNotCacheOriginalForever() {
        var lease = cache.claim(List.of(name), EN);
        cache.fail(lease);
        assertThat(cache.find(List.of(name), EN)).isEmpty();
        assertThat(cache.claim(List.of(name), EN)).isEmpty();
        clock.advance(Duration.ofSeconds(60));
        assertThat(cache.claim(List.of(name), EN)).hasSize(1);
    }

    @Test
    void deletedGroupCannotPublishAndPhysicalDeleteClearsCache() {
        var lease = cache.claim(List.of(name), EN).getFirst();
        jdbc.sql("update study_group set deleted_at = now() where id = :id")
                .param("id", group)
                .update();
        assertThat(cache.complete(lease, "late", TranslationProvider.GOOGLE)).isFalse();
        cleanup();
        assertThat(cache.find(List.of(name), EN)).isEmpty();
    }

    @Test
    void sourceEditWhileClaimIsPausedCannotLoseNewerCache() throws Exception {
        long lockKey = 119100000L + group;
        installClaimBarrier(lockKey);
        try (var blocker = dataSource.getConnection()) {
            blocker.setAutoCommit(false);
            try (var statement = blocker.createStatement()) {
                statement.execute("select pg_advisory_xact_lock(" + lockKey + ")");
            }
            try (var pool = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
                try {
                    var older = pool.submit(() -> cache.claim(List.of(name), EN));
                    await(() -> jdbc.sql("select count(*) from pg_stat_activity where wait_event = 'advisory'")
                                    .query(Integer.class)
                                    .single()
                            > 0);
                    var changed = new TranslationText(group, NAME, "새 이름");
                    var newer = pool.submit(() -> {
                        jdbc.sql("update study_group set name='새 이름' where id=:id")
                                .param("id", group)
                                .update();
                        var lease = cache.claim(List.of(changed), EN).getFirst();
                        return cache.complete(lease, "new result", TranslationProvider.AZURE);
                    });
                    await(() -> newer.isDone()
                            || jdbc.sql(
                                                    "select count(*) from pg_stat_activity where wait_event_type='Lock' and query like 'update study_group set name=%'")
                                            .query(Integer.class)
                                            .single()
                                    > 0);
                    blocker.commit();
                    var oldLeases = older.get(5, java.util.concurrent.TimeUnit.SECONDS);
                    assertThat(newer.get(5, java.util.concurrent.TimeUnit.SECONDS))
                            .isTrue();
                    assertThat(cache.find(List.of(changed), EN).values())
                            .singleElement()
                            .satisfies(v -> assertThat(v.text()).isEqualTo("new result"));
                    for (var old : oldLeases)
                        assertThat(cache.complete(old, "obsolete", TranslationProvider.GOOGLE))
                                .isFalse();
                } finally {
                    blocker.rollback();
                }
            }
        } finally {
            jdbc.sql("drop trigger by1191_claim_barrier on group_translation_cache")
                    .update();
            jdbc.sql("drop function by1191_claim_barrier()").update();
        }
    }

    void installClaimBarrier(long lockKey) {
        jdbc.sql("""
                create function by1191_claim_barrier() returns trigger language plpgsql as $$
                begin
                  if NEW.group_id = %d and NEW.source_hash = '%s' then
                    perform pg_advisory_xact_lock(%d);
                  end if;
                  return NEW;
                end $$
                """.formatted(group, name.sourceHash(), lockKey)).update();
        jdbc.sql(
                        "create trigger by1191_claim_barrier before insert on group_translation_cache for each row execute function by1191_claim_barrier()")
                .update();
    }

    static void await(java.util.function.BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(10);
        assertThat(condition.getAsBoolean()).isTrue();
    }
}
