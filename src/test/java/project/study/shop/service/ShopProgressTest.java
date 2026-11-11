package project.study.shop.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import org.junit.jupiter.api.Test;
import project.study.shop.dto.ShopCategory;
import project.study.shop.dto.ShopItem;
import project.study.shop.dto.UnlockType;

class ShopProgressTest {

    private static ShopItem priced(long id) {
        return new ShopItem(id, "P" + id, ShopCategory.TIMER_BACKGROUND, null, null, null, "상품", null, 100, null, null);
    }

    private static ShopItem reward(long id, UnlockType type, int value) {
        return new ShopItem(
                id, "R" + id, ShopCategory.TIMER_BACKGROUND, null, null, null, "보상", null, null, type, value);
    }

    @Test
    void 산_상품만_가진_것이고_조건_진행은_없다() {
        ShopProgress progress = new ShopProgress(Set.of(1L), 999_999, 999);

        assertThat(progress.owns(priced(1))).isTrue();
        assertThat(progress.owns(priced(2))).isFalse();
        assertThat(progress.unlockOf(priced(2))).isNull();
    }

    @Test
    void 보상_상품은_조건을_채우면_가진_것이다() {
        ShopProgress progress = new ShopProgress(Set.of(), 100 * 3600 - 1, 14);

        assertThat(progress.owns(reward(1, UnlockType.STREAK_DAYS, 14))).isTrue();
        assertThat(progress.owns(reward(2, UnlockType.STREAK_DAYS, 15))).isFalse();
        assertThat(progress.owns(reward(3, UnlockType.TOTAL_FOCUS_HOURS, 100))).isFalse();
        assertThat(progress.owns(reward(4, UnlockType.TOTAL_FOCUS_HOURS, 99))).isTrue();
        assertThat(progress.unlockOf(reward(3, UnlockType.TOTAL_FOCUS_HOURS, 100))
                        .currentValue())
                .isEqualTo(99);
    }
}
