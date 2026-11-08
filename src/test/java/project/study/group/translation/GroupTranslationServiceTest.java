package project.study.group.translation;

import static org.assertj.core.api.Assertions.assertThat;
import static project.study.group.translation.TranslationProvider.*;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import project.study.TestcontainersConfiguration;
import project.study.group.support.GroupProbe;
import project.study.room.support.MutableClock;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class GroupTranslationServiceTest {
    @Autowired
    JdbcClient jdbc;

    @Autowired
    PlatformTransactionManager manager;

    final MutableClock clock = MutableClock.at(Instant.parse("2026-11-08T10:00:00Z"));
    TranslationCacheRepository cache;
    TranslationBudget budget;
    long user;
    long group;
    List<TranslationText> texts;
    final List<TranslationProvider> calls = new ArrayList<>();

    @BeforeEach
    void setup() {
        GroupProbe probe = new GroupProbe(jdbc);
        user = probe.insertUser();
        group = probe.insertGroup(user, "PUBLIC", "새벽", "ETC");
        probe.setDescription(group, "함께 공부");
        texts = List.of(
                new TranslationText(group, TranslationField.NAME, "새벽"),
                new TranslationText(group, TranslationField.DESCRIPTION, "함께 공부"));
        cache = new TranslationCacheRepository(jdbc, manager, clock);
        budget = new TranslationBudget(
                jdbc, manager, clock, new TranslationBudget.Limits(13000, 400000, 2000000, 1000000));
        jdbc.sql("delete from group_translation_usage").update();
        jdbc.sql(
                        "update group_translation_provider_state set initialized=true,suspended=false,blocked_until=null,day_key='2026-11-08',month_key='2026-11'")
                .update();
        for (var p : TranslationProvider.values()) {
            seed(p, "DAY", "2026-11-08");
            seed(p, "MONTH", "2026-11");
            seed(p, "LIFETIME", "ALL");
        }
    }

    void seed(TranslationProvider p, String type, String key) {
        jdbc.sql("insert into group_translation_usage values (:p,:type,:key,0)")
                .param("p", p.name())
                .param("type", type)
                .param("key", key)
                .update();
    }

    @AfterEach
    void cleanup() {
        jdbc.sql("delete from study_group_member where group_id in (select id from study_group where owner_id=:id)")
                .param("id", user)
                .update();
        jdbc.sql("delete from study_group where owner_id=:id").param("id", user).update();
        jdbc.sql("delete from users where id=:id").param("id", user).update();
    }

    GroupTranslationService service(List<TextTranslator> translators) {
        return new GroupTranslationService(
                cache,
                budget,
                translators,
                new TranslationConfig.Settings(true, Map.of(GOOGLE, true, AZURE, true, DEEPL, true)),
                clock,
                new SimpleMeterRegistry());
    }

    TextTranslator translator(TranslationProvider p, TextTranslator.Failure failure) {
        return new TextTranslator() {
            public TranslationProvider provider() {
                return p;
            }

            public TranslationReply translate(List<String> input, TranslationLanguage language, Duration timeout) {
                assertThat(TransactionSynchronizationManager.isActualTransactionActive())
                        .isFalse();
                assertThat(jdbc.sql("select sum(reserved_characters) from group_translation_usage where provider=:p")
                                .param("p", p.name())
                                .query(Long.class)
                                .single())
                        .isPositive();
                assertThat(input).hasSizeLessThanOrEqualTo(50);
                assertThat(input.stream()
                                .mapToLong(t -> t.codePointCount(0, t.length()))
                                .sum())
                        .isLessThanOrEqualTo(5000);
                calls.add(p);
                return failure == Failure.NONE
                        ? new TranslationReply(
                                input.stream().map(t -> "translated:" + t).toList(), failure, null)
                        : TranslationReply.failed(failure);
            }
        };
    }

    @Test
    void coldFieldsAreBatchedAndSubsequentReadersConsumeNoMoreQuota() {
        var service = service(List.of(translator(GOOGLE, TextTranslator.Failure.NONE)));
        assertThat(service.translate(texts, TranslationLanguage.EN)).hasSize(2);
        long remaining = budget.available(GOOGLE);
        assertThat(service.translate(texts, TranslationLanguage.EN)).hasSize(2);
        assertThat(calls).containsExactly(GOOGLE);
        assertThat(budget.available(GOOGLE)).isEqualTo(remaining);
    }

    @Test
    void failuresReserveEachAttemptThenSwitchToNextFreeProvider() {
        var service = service(List.of(
                translator(GOOGLE, TextTranslator.Failure.TEMPORARY),
                translator(AZURE, TextTranslator.Failure.QUOTA),
                translator(DEEPL, TextTranslator.Failure.NONE)));
        assertThat(service.translate(texts, TranslationLanguage.JA)).hasSize(2);
        assertThat(calls).containsExactly(GOOGLE, AZURE, DEEPL);
        assertThat(budget.available(DEEPL)).isEqualTo(999993);
        assertThat(budget.available(AZURE)).isZero();
    }

    @Test
    void exhaustedOrPartlyAvailableBudgetRoutesRemainderToAzure() {
        assertThat(budget.tryReserve(GOOGLE, 12998)).isTrue();
        var service = service(List.of(
                translator(GOOGLE, TextTranslator.Failure.NONE), translator(AZURE, TextTranslator.Failure.NONE)));
        assertThat(service.translate(texts, TranslationLanguage.ZH_CN)).hasSize(2);
        assertThat(calls).containsExactly(GOOGLE, AZURE);
        assertThat(budget.available(GOOGLE)).isZero();
        assertThat(budget.available(AZURE)).isEqualTo(1999995);
    }

    @Test
    void allFailuresAndActiveLeaseReturnOriginalAndFeatureOffNeverCalls() {
        var service = service(List.of(translator(GOOGLE, TextTranslator.Failure.TEMPORARY)));
        assertThat(service.translate(texts, TranslationLanguage.ZH_HK)).isEmpty();
        assertThat(service.translate(texts, TranslationLanguage.ZH_HK)).isEmpty();
        assertThat(calls).containsExactly(GOOGLE);
        cache.claim(texts, TranslationLanguage.KO);
        assertThat(service.translate(texts, TranslationLanguage.KO)).isEmpty();
        var off = new GroupTranslationService(
                cache,
                budget,
                List.of(translator(AZURE, TextTranslator.Failure.NONE)),
                new TranslationConfig.Settings(false, Map.of(AZURE, true)),
                clock,
                new SimpleMeterRegistry());
        assertThat(off.translate(texts, TranslationLanguage.EN)).isEmpty();
        assertThat(calls).containsExactly(GOOGLE);
    }

    @Test
    void deadlinePreventsStartingAnotherProviderAfterSlowFailure() {
        TextTranslator slow = new TextTranslator() {
            public TranslationProvider provider() {
                return GOOGLE;
            }

            public TranslationReply translate(List<String> input, TranslationLanguage language, Duration timeout) {
                calls.add(GOOGLE);
                try {
                    Thread.sleep(3050);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return TranslationReply.failed(Failure.TEMPORARY);
            }
        };
        assertThat(service(List.of(slow, translator(AZURE, TextTranslator.Failure.NONE)))
                        .translate(texts, TranslationLanguage.EN))
                .isEmpty();
        assertThat(calls).containsExactly(GOOGLE);
    }

    @Test
    void moreThanFiftyNamesAreSplitWithoutRetranslatingCachedFields() {
        List<TranslationText> many = new ArrayList<>();
        var probe = new GroupProbe(jdbc);
        for (int i = 0; i < 51; i++) {
            String title = "공부-" + i;
            long id = probe.insertGroup(user, "PUBLIC", title, "ETC");
            many.add(new TranslationText(id, TranslationField.NAME, title));
        }
        var service = service(List.of(translator(GOOGLE, TextTranslator.Failure.NONE)));
        assertThat(service.translate(many, TranslationLanguage.EN)).hasSize(51);
        assertThat(calls).containsExactly(GOOGLE, GOOGLE);
        assertThat(service.translate(many, TranslationLanguage.EN)).hasSize(51);
        assertThat(calls).hasSize(2);
    }

    @Test
    void characterLimitSplitsOnFieldBoundaryAndBlankFieldsAreSkipped() {
        String longDescription = "가".repeat(5000);
        new GroupProbe(jdbc).setDescription(group, longDescription);
        var description = new TranslationText(group, TranslationField.DESCRIPTION, longDescription);
        var blank = new TranslationText(group, TranslationField.DESCRIPTION, " ");
        var service = service(List.of(translator(GOOGLE, TextTranslator.Failure.NONE)));
        assertThat(service.translate(List.of(texts.getFirst(), description, blank), TranslationLanguage.EN))
                .hasSize(2);
        assertThat(calls).containsExactly(GOOGLE, GOOGLE);
    }

    @Test
    void unchangedTranslationOutputIsStillCached() {
        TextTranslator same = new TextTranslator() {
            public TranslationProvider provider() {
                return GOOGLE;
            }

            public TranslationReply translate(List<String> input, TranslationLanguage language, Duration timeout) {
                calls.add(GOOGLE);
                return new TranslationReply(input, Failure.NONE, null);
            }
        };
        var service = service(List.of(same));
        assertThat(service.translate(texts, TranslationLanguage.KO).values()).containsExactlyInAnyOrder("새벽", "함께 공부");
        assertThat(service.translate(texts, TranslationLanguage.KO)).hasSize(2);
        assertThat(calls).containsExactly(GOOGLE);
    }
}
