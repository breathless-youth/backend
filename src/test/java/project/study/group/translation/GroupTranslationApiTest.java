package project.study.group.translation;

import static org.assertj.core.api.Assertions.assertThat;
import static project.study.group.support.GroupApiTestSupport.*;

import java.time.Clock;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.transaction.PlatformTransactionManager;
import project.study.TestcontainersConfiguration;
import project.study.group.support.GroupProbe;

@SpringBootTest(properties = "app.group-translation.enabled=true")
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class GroupTranslationApiTest {
    @Autowired
    MockMvcTester mvc;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    PlatformTransactionManager manager;

    @Autowired
    Clock clock;

    long owner;
    long stranger;
    long group;
    String name;
    TranslationCacheRepository cache;

    @BeforeEach
    void setup() {
        GroupProbe probe = new GroupProbe(jdbc);
        owner = probe.insertUser();
        stranger = probe.insertUser();
        name = "번역 공부방-" + UUID.randomUUID().toString().substring(0, 4);
        group = probe.insertGroup(owner, "PUBLIC", name, "ETC");
        probe.setDescription(group, "함께 공부");
        cache = new TranslationCacheRepository(jdbc, manager, clock);
    }

    @AfterEach
    void cleanup() {
        jdbc.sql("delete from study_group_member where group_id=:id")
                .param("id", group)
                .update();
        jdbc.sql("delete from study_group where id=:id").param("id", group).update();
        jdbc.sql("delete from users where id in (:ids)")
                .param("ids", List.of(owner, stranger))
                .update();
    }

    void warm(TranslationLanguage language, boolean description) {
        var texts = description
                ? List.of(
                        new TranslationText(group, TranslationField.NAME, name),
                        new TranslationText(group, TranslationField.DESCRIPTION, "함께 공부"))
                : List.of(new TranslationText(group, TranslationField.NAME, name));
        for (var lease : cache.claim(texts, language)) {
            assertThat(cache.complete(
                            lease,
                            lease.source().field() == TranslationField.NAME ? "Study group" : "Study together",
                            TranslationProvider.GOOGLE))
                    .isTrue();
        }
    }

    @Test
    void allFourReadEndpointsExposeTranslationsAndPreserveOriginal() {
        warm(TranslationLanguage.EN, true);
        assertThat(v1Get(mvc, "/api/groups/" + group, owner).header("Accept-Language", "en"))
                .hasStatusOk()
                .bodyJson()
                .hasPathSatisfying("$.name", v -> assertThat(v).isEqualTo(name))
                .hasPathSatisfying("$.translation.name", v -> assertThat(v).isEqualTo("Study group"));
        assertThat(v1Get(mvc, "/api/groups/public/" + group, stranger).header("Accept-Language", "en"))
                .hasStatusOk()
                .bodyJson()
                .hasPathSatisfying("$.description", v -> assertThat(v).isEqualTo("함께 공부"))
                .hasPathSatisfying(
                        "$.translation.description", v -> assertThat(v).isEqualTo("Study together"));
        assertThat(v1Get(mvc, "/api/groups/me", owner).header("Accept-Language", "en"))
                .hasStatusOk()
                .bodyJson()
                .hasPathSatisfying(
                        "$.groups[0].translation.name", v -> assertThat(v).isEqualTo("Study group"));
        assertThat(v1Get(mvc, "/api/groups/public", stranger)
                        .param("query", name)
                        .header("Accept-Language", "en"))
                .hasStatusOk()
                .bodyJson()
                .hasPathSatisfying(
                        "$.groups[0].translation.name", v -> assertThat(v).isEqualTo("Study group"));
    }

    @Test
    void sameLanguageReaderGetsNoTranslationEvenWhenOldCacheExists() {
        warm(TranslationLanguage.KO, true);
        assertThat(v1Get(mvc, "/api/groups/public/" + group, stranger).header("Accept-Language", "ko"))
                .hasStatusOk()
                .bodyJson()
                .hasPathSatisfying("$.name", v -> assertThat(v).isEqualTo(name))
                .hasPathSatisfying("$.translation", v -> assertThat(v).isNull());
    }

    @Test
    void fourLocalesUseSeparateCacheAndMissingFieldFallsBackIndividually() {
        // 한국어 원문이라 ko는 번역 대상이 아니다 — 아래 별도 테스트에서 확인한다.
        for (var language : List.of(TranslationLanguage.EN, TranslationLanguage.JA, TranslationLanguage.ZH_TW)) {
            warm(language, false);
            assertThat(v1Get(mvc, "/api/groups/public/" + group, stranger).header("Accept-Language", language.locale()))
                    .hasStatusOk()
                    .bodyJson()
                    .hasPathSatisfying(
                            "$.translation.language", v -> assertThat(v).isEqualTo(language.locale()))
                    .hasPathSatisfying("$.translation.name", v -> assertThat(v).isEqualTo("Study group"))
                    .hasPathSatisfying(
                            "$.translation.description", v -> assertThat(v).isNull());
        }
    }

    @Test
    void missingOrUnsupportedHeaderLeavesOriginalAndPermissionsStillApply() {
        warm(TranslationLanguage.EN, true);
        assertThat(v1Get(mvc, "/api/groups/public/" + group, stranger))
                .hasStatusOk()
                .bodyJson()
                .hasPathSatisfying("$.translation", v -> assertThat(v).isNull());
        assertThat(v1Get(mvc, "/api/groups/public/" + group, stranger).header("Accept-Language", "fr"))
                .hasStatusOk()
                .bodyJson()
                .hasPathSatisfying("$.translation", v -> assertThat(v).isNull());
        assertErrorCode(
                v1Get(mvc, "/api/groups/" + group, stranger).header("Accept-Language", "en"),
                HttpStatus.FORBIDDEN,
                "NOT_GROUP_MEMBER");
        jdbc.sql("update study_group set deleted_at=now() where id=:id")
                .param("id", group)
                .update();
        assertErrorCode(
                v1Get(mvc, "/api/groups/public/" + group, stranger).header("Accept-Language", "en"),
                HttpStatus.NOT_FOUND,
                "GROUP_NOT_FOUND");
    }

    @Test
    void translatedReadsPreserveOpenJoinPolicyAcrossFourEndpoints() {
        jdbc.sql("update study_group set join_policy='OPEN' where id=:id")
                .param("id", group)
                .update();
        warm(TranslationLanguage.EN, true);
        assertThat(v1Get(mvc, "/api/groups/" + group, owner).header("Accept-Language", "en"))
                .hasStatusOk()
                .bodyJson()
                .hasPathSatisfying("$.joinPolicy", v -> assertThat(v).isEqualTo("OPEN"));
        assertThat(v1Get(mvc, "/api/groups/public/" + group, stranger).header("Accept-Language", "en"))
                .hasStatusOk()
                .bodyJson()
                .hasPathSatisfying("$.joinPolicy", v -> assertThat(v).isEqualTo("OPEN"));
        assertThat(v1Get(mvc, "/api/groups/me", owner).header("Accept-Language", "en"))
                .hasStatusOk()
                .bodyJson()
                .hasPathSatisfying("$.groups[0].joinPolicy", v -> assertThat(v).isEqualTo("OPEN"));
        assertThat(v1Get(mvc, "/api/groups/public", stranger)
                        .param("query", name)
                        .header("Accept-Language", "en"))
                .hasStatusOk()
                .bodyJson()
                .hasPathSatisfying("$.groups[0].joinPolicy", v -> assertThat(v).isEqualTo("OPEN"));
    }

    @Test
    void allTranslatedGetResponsesVaryByAcceptLanguage() {
        var requests = List.of(
                v1Get(mvc, "/api/groups/" + group, owner),
                v1Get(mvc, "/api/groups/public/" + group, stranger),
                v1Get(mvc, "/api/groups/me", owner),
                v1Get(mvc, "/api/groups/public", stranger).param("query", name));
        for (var request : requests) {
            var response = request.header("Accept-Language", "en").exchange().getResponse();
            assertThat(response.getStatus()).isEqualTo(200);
            assertThat(response.getHeaders("Vary").stream()
                            .flatMap(v -> java.util.Arrays.stream(v.split(",")))
                            .map(String::trim)
                            .toList())
                    .contains("Accept-Language");
        }
    }

    @Test
    void translationNameMayBeNullWhileDescriptionIsTranslated() {
        var description = new TranslationText(group, TranslationField.DESCRIPTION, "함께 공부");
        var lease = cache.claim(List.of(description), TranslationLanguage.EN).getFirst();
        assertThat(cache.complete(lease, "Study together", TranslationProvider.GOOGLE))
                .isTrue();
        assertThat(v1Get(mvc, "/api/groups/public/" + group, stranger).header("Accept-Language", "en"))
                .hasStatusOk()
                .bodyJson()
                .hasPathSatisfying("$.name", v -> assertThat(v).isEqualTo(name))
                .hasPathSatisfying("$.translation.name", v -> assertThat(v).isNull())
                .hasPathSatisfying(
                        "$.translation.description", v -> assertThat(v).isEqualTo("Study together"));
    }
}
