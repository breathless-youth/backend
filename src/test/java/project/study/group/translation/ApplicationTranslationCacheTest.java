package project.study.group.translation;

import static org.assertj.core.api.Assertions.assertThat;
import static project.study.group.translation.TranslationField.*;
import static project.study.group.translation.TranslationLanguage.*;

import java.time.Instant;
import java.util.List;
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

/** 신청 소개 글 번역 캐시 — 신청 행을 기준으로 하고 그룹 캐시와 섞이지 않는다 (BY-1301). */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class ApplicationTranslationCacheTest {
    @Autowired
    JdbcClient jdbc;

    @Autowired
    PlatformTransactionManager manager;

    final MutableClock clock = MutableClock.at(Instant.parse("2026-11-16T10:00:00Z"));
    TranslationCacheRepository cache;
    long owner;
    long applicant;
    long group;
    long application;
    TranslationText introduction;

    @BeforeEach
    void setup() {
        cache = new TranslationCacheRepository(jdbc, manager, clock);
        GroupProbe probe = new GroupProbe(jdbc);
        owner = probe.insertUser();
        applicant = probe.insertUser();
        group = probe.insertGroup(owner, "PUBLIC", "새벽 공부", "ETC");
        application = probe.insertApplication(group, applicant, "PENDING", Instant.parse("2026-11-19T10:00:00Z"));
        introduction = new TranslationText(application, INTRODUCTION, "안녕하세요");
    }

    @AfterEach
    void cleanup() {
        jdbc.sql("delete from study_group_application where group_id = :id")
                .param("id", group)
                .update();
        jdbc.sql("delete from study_group_member where group_id = :id")
                .param("id", group)
                .update();
        jdbc.sql("delete from study_group where id = :id").param("id", group).update();
        jdbc.sql("delete from users where id in (:ids)")
                .param("ids", List.of(owner, applicant))
                .update();
    }

    @Test
    void pendingApplicationIntroductionIsCachedByApplicationRow() {
        var lease = cache.claim(List.of(introduction), EN).getFirst();
        assertThat(cache.complete(lease, "Hello", TranslationProvider.AZURE)).isTrue();
        assertThat(cache.find(List.of(introduction), EN).values())
                .singleElement()
                .satisfies(v -> assertThat(v.text()).isEqualTo("Hello"));
        // 같은 id의 그룹 칸과 섞이지 않는다
        assertThat(cache.find(List.of(new TranslationText(application, NAME, "안녕하세요")), EN))
                .isEmpty();
    }

    @Test
    void answeredApplicationIsNotClaimedAndLateResultIsDropped() {
        var lease = cache.claim(List.of(introduction), EN).getFirst();
        jdbc.sql("update study_group_application set status = 'DECLINED' where id = :id")
                .param("id", application)
                .update();
        assertThat(cache.complete(lease, "late", TranslationProvider.GOOGLE)).isFalse();
        assertThat(cache.claim(List.of(introduction), JA)).isEmpty();
    }

    @Test
    void expiredButStillPendingApplicationIsNotClaimed() {
        clock.advance(java.time.Duration.ofDays(4));
        assertThat(cache.claim(List.of(introduction), EN)).isEmpty();
    }

    @Test
    void groupAndApplicationTextsResolveTogether() {
        var name = new TranslationText(group, NAME, "새벽 공부");
        for (var lease : cache.claim(List.of(introduction, name), EN))
            assertThat(cache.complete(lease, "t-" + lease.source().field(), TranslationProvider.DEEPL))
                    .isTrue();
        assertThat(cache.find(List.of(name, introduction), EN)).hasSize(2);
    }

    @Test
    void deletingApplicationRowClearsItsTranslations() {
        var lease = cache.claim(List.of(introduction), EN).getFirst();
        assertThat(cache.complete(lease, "Hello", TranslationProvider.AZURE)).isTrue();
        jdbc.sql("delete from study_group_application where id = :id")
                .param("id", application)
                .update();
        assertThat(jdbc.sql("select count(*) from group_application_translation_cache where application_id = :id")
                        .param("id", application)
                        .query(Integer.class)
                        .single())
                .isZero();
    }
}
