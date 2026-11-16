package project.study.group.translation;

import static org.assertj.core.api.Assertions.assertThat;
import static project.study.group.support.GroupApiTestSupport.*;

import java.time.Clock;
import java.util.Arrays;
import java.util.List;
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

/** 그룹장용 신청 목록의 신청 소개 글 번역 (BY-1301). */
@SpringBootTest(properties = "app.group-translation.enabled=true")
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class GroupApplicationTranslationApiTest {
    @Autowired
    MockMvcTester mvc;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    PlatformTransactionManager manager;

    @Autowired
    Clock clock;

    long owner;
    long applicant;
    long group;
    long application;
    TranslationCacheRepository cache;

    @BeforeEach
    void setup() {
        GroupProbe probe = new GroupProbe(jdbc);
        owner = probe.insertUser();
        applicant = probe.insertUser();
        group = probe.insertGroup(owner, "PUBLIC");
        application = probe.insertApplication(
                group, applicant, "PENDING", clock.instant().plusSeconds(24 * 3600));
        cache = new TranslationCacheRepository(jdbc, manager, clock);
    }

    @AfterEach
    void cleanup() {
        jdbc.sql("delete from study_group_application where group_id=:id")
                .param("id", group)
                .update();
        jdbc.sql("delete from study_group_member where group_id=:id")
                .param("id", group)
                .update();
        jdbc.sql("delete from study_group where id=:id").param("id", group).update();
        jdbc.sql("delete from users where id in (:ids)")
                .param("ids", List.of(owner, applicant))
                .update();
    }

    void warm(TranslationLanguage language, String text) {
        var lease = cache.claim(
                        List.of(new TranslationText(application, TranslationField.INTRODUCTION, "안녕하세요")), language)
                .getFirst();
        assertThat(cache.complete(lease, text, TranslationProvider.AZURE)).isTrue();
    }

    String uri() {
        return "/api/groups/" + group + "/applications";
    }

    @Test
    void ownerSeesTranslatedIntroductionNextToOriginal() {
        warm(TranslationLanguage.EN, "Hello");
        var response = v1Get(mvc, uri(), owner)
                .header("Accept-Language", "en-US,en;q=0.9")
                .exchange();
        assertThat(response)
                .hasStatusOk()
                .bodyJson()
                .hasPathSatisfying(
                        "$.applications[0].introduction", v -> assertThat(v).isEqualTo("안녕하세요"))
                .hasPathSatisfying(
                        "$.applications[0].translation.language",
                        v -> assertThat(v).isEqualTo("en"))
                .hasPathSatisfying(
                        "$.applications[0].translation.introduction",
                        v -> assertThat(v).isEqualTo("Hello"));
        assertThat(response.getResponse().getHeaders("Vary").stream()
                        .flatMap(v -> Arrays.stream(v.split(",")))
                        .map(String::trim)
                        .toList())
                .contains("Accept-Language");
    }

    @Test
    void sameLanguageMissingHeaderOrUncachedLeavesTranslationNull() {
        warm(TranslationLanguage.KO, "안녕");
        assertThat(v1Get(mvc, uri(), owner).header("Accept-Language", "ko"))
                .hasStatusOk()
                .bodyJson()
                .hasPathSatisfying(
                        "$.applications[0].translation", v -> assertThat(v).isNull());
        assertThat(v1Get(mvc, uri(), owner))
                .hasStatusOk()
                .bodyJson()
                .hasPathSatisfying(
                        "$.applications[0].translation", v -> assertThat(v).isNull());
        // 테스트에는 켜진 공급자가 없어 캐시에 없는 언어는 원문으로 남는다
        assertThat(v1Get(mvc, uri(), owner).header("Accept-Language", "ja"))
                .hasStatusOk()
                .bodyJson()
                .hasPathSatisfying(
                        "$.applications[0].translation", v -> assertThat(v).isNull());
    }

    @Test
    void nonOwnerIsRejectedBeforeAnyTranslationWork() {
        assertErrorCode(
                v1Get(mvc, uri(), applicant).header("Accept-Language", "en"), HttpStatus.FORBIDDEN, "NOT_GROUP_OWNER");
        // 권한 확인이 먼저라 캐시 작업권도 만들지 않는다 — 그룹장 조회는 만든다
        assertThat(cacheRows()).isZero();
        assertThat(v1Get(mvc, uri(), owner).header("Accept-Language", "en")).hasStatusOk();
        assertThat(cacheRows()).isOne();
    }

    @Test
    void onlyTheOldestApplicationsAreTranslatedPerRequest() {
        GroupProbe probe = new GroupProbe(jdbc);
        List<Long> extra = new java.util.ArrayList<>();
        for (int i = 0; i < LocalizedGroupReader.MAX_TRANSLATED_APPLICATIONS; i++) {
            long user = probe.insertUser();
            extra.add(user);
            probe.insertApplication(group, user, "PENDING", clock.instant().plusSeconds(24 * 3600 + i + 1));
        }
        try {
            assertThat(v1Get(mvc, uri(), owner).header("Accept-Language", "en")).hasStatusOk();
            assertThat(cacheRows()).isEqualTo(LocalizedGroupReader.MAX_TRANSLATED_APPLICATIONS);
        } finally {
            cleanup();
            jdbc.sql("delete from users where id in (:ids)").param("ids", extra).update();
        }
    }

    int cacheRows() {
        return jdbc.sql("""
                select count(*) from group_application_translation_cache c
                  join study_group_application a on a.id = c.application_id where a.group_id = :id""").param("id", group).query(Integer.class).single();
    }
}
