package project.study.point;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import project.study.TestcontainersConfiguration;

/**
 * 포인트·상점·마스코트·미션 API의 성공 응답 모양이 Swagger에 실린다. 에러 응답만 적으면 springdoc이 200을 빼서 앱이 응답 필드를 문서에서 볼
 * 수 없었다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class PointSwaggerDocsTest {

    @Autowired
    private MockMvcTester mvc;

    @ParameterizedTest
    @CsvSource({
        "/api/points, get, PointBalanceResponse",
        "/api/points/history, get, PointHistoryResponse",
        "/api/points/study-reward, get, StudyRewardResponse",
        "/api/points/earn, get, EarnOverviewResponse",
        "/api/points/rewards/attendance/claim, post, ClaimResponse",
        "/api/points/rewards/daily-goal/claim, post, ClaimResponse",
        "/api/points/rewards/medals/claim, post, MedalClaimAllResponse",
        "/api/points/rewards/medals/{recordId}/claim, post, ClaimResponse",
        "/api/shop/items, get, ShopItemListResponse",
        "/api/shop/items/{itemId}/purchase, post, PurchaseResponse",
        "/api/decorations, get, DecorationResponse",
        "/api/decorations/timer-background, put, DecorationResponse",
        "/api/decorations/mascots/{mascotCode}/accessories, put, DecorationResponse",
        "/api/mascots, get, MascotListResponse",
        "/api/mascots/selected, put, MascotListResponse",
        "/api/mascots/unlocks/seen, post, MascotListResponse",
        "/api/mascots/{code}/purchase, post, MascotPurchaseResponse",
        "/api/home/mission/steps/{step}/complete, post, HomeMissionResponse",
        "/api/home/mission/rewards/claim, post, ClaimResponse",
    })
    void 성공_응답의_모양이_문서에_실린다(String path, String method, String schema) {
        assertThat(mvc.get().uri("/v3/api-docs"))
                .hasStatusOk()
                .bodyJson()
                .hasPathSatisfying(
                        "$.paths['" + path + "']." + method + ".responses['200'].content..schema['$ref']",
                        v -> assertThat(v).asList().containsOnly("#/components/schemas/" + schema));
    }

    @Test
    void 받기_응답의_세_필드가_모두_문서에_있다() {
        assertThat(mvc.get().uri("/v3/api-docs"))
                .hasStatusOk()
                .bodyJson()
                .hasPathSatisfying(
                        "$.components.schemas.ClaimResponse.properties",
                        v -> assertThat(v)
                                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
                                .containsKeys("granted", "points", "balance"));
    }
}
