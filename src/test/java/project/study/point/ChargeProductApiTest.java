package project.study.point;

import static org.assertj.core.api.Assertions.assertThat;
import static project.study.support.AuthTestSupport.asUser;

import java.util.UUID;
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

/** 충전 상품 목록과, 충전이 설정되지 않은 서버의 알림 경로. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class ChargeProductApiTest {

    @Autowired
    MockMvcTester mvc;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    PointShopFixture fixture;

    @Test
    void 충전_상품_목록은_파는_것만_순서대로_준다() {
        long user = fixture.member();
        String first = product(1000, 100);
        jdbc.sql("UPDATE point_product SET sort_order = -1 WHERE store_product_id = :p")
                .param("p", first)
                .update();
        String hidden = product(500, 0);
        jdbc.sql("UPDATE point_product SET active = false WHERE store_product_id = :p")
                .param("p", hidden)
                .update();

        MvcTestResult result = mvc.get()
                .uri("/api/points/products")
                .with(asUser(user))
                .header(ApiVersionConfig.HEADER, "1")
                .exchange();

        assertThat(result)
                .hasStatusOk()
                .bodyJson()
                .extractingPath("$.items[*].storeProductId")
                .asArray()
                .contains(first)
                .doesNotContain(hidden);
        assertThat(result)
                .bodyJson()
                .extractingPath("$.items[?(@.storeProductId == '" + first + "')].totalPoints")
                .asArray()
                .containsExactly(1100);
        assertThat(mvc.get()
                        .uri("/api/points/products")
                        .with(asUser(fixture.guest()))
                        .header(ApiVersionConfig.HEADER, "1")
                        .exchange())
                .hasStatus(HttpStatus.FORBIDDEN);
    }

    @Test
    void 충전이_설정되지_않은_서버는_알림_경로가_404다() {
        assertThat(mvc.post()
                        .uri("/api/points/purchases/revenuecat/webhook")
                        .header("Authorization", "")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"api_version\":\"1.0\"}")
                        .exchange())
                .hasStatus(HttpStatus.NOT_FOUND);
    }

    private String product(int points, int bonus) {
        String storeProductId = "points_" + UUID.randomUUID();
        jdbc.sql("INSERT INTO point_product (store_product_id, points, bonus_points) VALUES (:p, :points, :bonus)")
                .param("p", storeProductId)
                .param("points", points)
                .param("bonus", bonus)
                .update();
        return storeProductId;
    }
}
