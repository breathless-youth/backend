package project.study.shop;

import static org.assertj.core.api.Assertions.assertThat;
import static project.study.support.AuthTestSupport.asUser;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import project.study.TestcontainersConfiguration;
import project.study.config.ApiVersionConfig;
import project.study.point.PointShopFixture;
import project.study.shop.dto.AccessorySlot;
import project.study.shop.dto.MascotAppearance;
import project.study.shop.service.DecorationService;

/** 다른 사람에게 보이는 모습(그룹 책상)과 상품 이름의 언어. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class ShopAppearanceTest {

    @Autowired
    MockMvcTester mvc;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    PointShopFixture fixture;

    @Autowired
    DecorationService decorations;

    @Test
    void 선택한_마스코트가_낀_소품만_보이고_고른_적_없으면_첫_마스코트다() {
        long dressed = fixture.member();
        long plain = fixture.guest();
        long plannerHat = fixture.pricedItem("PLANNER", 10);
        long bookHat = fixture.pricedItem("BOOK", 10);
        wear(dressed, "PLANNER", plannerHat);
        wear(dressed, "BOOK", bookHat);
        jdbc.sql("INSERT INTO user_mascot (user_id, selected_mascot_code) VALUES (:u, 'PLANNER')")
                .param("u", dressed)
                .update();

        Map<Long, MascotAppearance> appearances = decorations.appearancesOf(List.of(dressed, plain));

        assertThat(appearances.get(dressed).mascotCode()).isEqualTo("PLANNER");
        assertThat(appearances.get(dressed).accessories()).singleElement().satisfies(accessory -> {
            assertThat(accessory.slot()).isEqualTo(AccessorySlot.HEAD);
            assertThat(accessory.item().itemId()).isEqualTo(plannerHat);
        });
        assertThat(appearances.get(plain)).isEqualTo(new MascotAppearance("BOOK", List.of()));
        assertThat(decorations.appearancesOf(List.of())).isEmpty();
    }

    @Test
    void 상품_이름과_구매_기록은_요청한_언어로_주고_번역이_없으면_한국어다() {
        long user = fixture.member();
        fixture.givePoints(user, 100);
        long item = fixture.pricedItem(null, 10);
        jdbc.sql("UPDATE shop_item SET names = CAST(:names AS jsonb) WHERE id = :id")
                .param("names", "{\"en\":\"Rainy window\"}")
                .param("id", item)
                .update();
        assertThat(mvc.post()
                        .uri("/api/shop/items/" + item + "/purchase")
                        .header(ApiVersionConfig.HEADER, "1")
                        .with(asUser(user))
                        .exchange())
                .hasStatusOk();

        assertThat(nameIn(user, item, "en-US")).isEqualTo("Rainy window");
        assertThat(nameIn(user, item, "ja-JP")).isEqualTo("상품");
        assertThat(nameIn(user, item, null)).isEqualTo("상품");
        assertThat(mvc.get()
                        .uri("/api/points/history?type=SPENT")
                        .header(ApiVersionConfig.HEADER, "1")
                        .header("Accept-Language", "en")
                        .with(asUser(user))
                        .exchange())
                .bodyJson()
                .hasPathSatisfying("$.items[0].label", v -> assertThat(v).isEqualTo("Rainy window"));
    }

    private String nameIn(long user, long itemId, String language) {
        var request = mvc.get()
                .uri("/api/shop/items")
                .header(ApiVersionConfig.HEADER, "1")
                .with(asUser(user));
        if (language != null) {
            request = request.header("Accept-Language", language);
        }
        String[] name = new String[1];
        assertThat(request.exchange())
                .hasStatusOk()
                .bodyJson()
                .extractingPath("$.items[?(@.id == " + itemId + ")].name")
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.LIST)
                .singleElement()
                .satisfies(v -> name[0] = (String) v);
        return name[0];
    }

    private void wear(long user, String mascot, long itemId) {
        jdbc.sql("INSERT INTO user_mascot_accessory (user_id, mascot_code, slot, item_id) VALUES (:u, :m, 'HEAD', :i)")
                .param("u", user)
                .param("m", mascot)
                .param("i", itemId)
                .update();
    }
}
