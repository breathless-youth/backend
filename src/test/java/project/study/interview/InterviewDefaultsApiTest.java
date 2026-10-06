package project.study.interview;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.transaction.annotation.Transactional;
import project.study.TestcontainersConfiguration;
import project.study.config.ApiVersionConfig;
import project.study.support.AuthTestSupport;

/** V24가 넣은 기본값으로 인터뷰 모집이 켜진 채 시작하는지 — 운영에서 SQL을 따로 넣지 않아도 된다 (ADR-0027). */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@Transactional
class InterviewDefaultsApiTest {

    private static final String FORM_URL =
            "https://docs.google.com/forms/d/e/1FAIpQLSc4sbX8ALpV9qUEYrXqGcXc1T9OD_r1vcXfWUAXw66EGjENrw/viewform?usp=pp_url&entry.1606714956=NICKNAME";

    @Autowired
    private MockMvcTester mvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private long userId;

    @BeforeEach
    void setUp() {
        // 세션이 없는 새 사용자 = 1번 그룹
        userId = jdbcTemplate.queryForObject(
                "INSERT INTO users (provider, provider_user_id) VALUES ('DEVICE', ?) RETURNING id",
                Long.class,
                UUID.randomUUID().toString());
    }

    private MockMvcTester.MockMvcRequestBuilder get(String uri) {
        return mvc.get()
                .uri(uri)
                .header(ApiVersionConfig.HEADER, ApiVersionConfig.DEFAULT_VERSION)
                .with(AuthTestSupport.asUser(userId));
    }

    @Test
    void 설정_입구가_켜진_채로_구글폼_링크를_내려준다() {
        assertThat(get("/api/interview/status")).hasStatusOk().bodyJson().isStrictlyEqualTo("""
                        {"cardEligible": false, "cardUrl": null, "settingsEnabled": true, "settingsUrl": "%s"}""".formatted(FORM_URL));
    }

    @Test
    void 일번_그룹에게_인터뷰_공지가_내려간다() {
        assertThat(get("/api/notices/active")).hasStatusOk().bodyJson().isLenientlyEqualTo("""
                        [{"title": "포메에 의견을 들려주실 분을 찾아요",
                          "content": "아직 타이머를 안 써보셨어도 괜찮아요.\\n15분 통화로 솔직한 이야기를 들려주세요.",
                          "imageUrl": "/images/interview/mascot-phone.png",
                          "audience": "G1_NOT_STARTED",
                          "badgeText": "스타벅스 기프티콘 100% 증정",
                          "buttonText": "인터뷰 신청하기",
                          "buttonUrl": "FORM_URL"}]""".replace(
                        "FORM_URL", FORM_URL));
    }
}
