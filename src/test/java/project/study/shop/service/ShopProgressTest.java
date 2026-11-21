package project.study.shop.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import org.junit.jupiter.api.Test;
import project.study.shop.dto.AssetType;
import project.study.shop.dto.ShopCategory;
import project.study.shop.dto.ShopItem;
import project.study.shop.dto.UnlockType;
import project.study.shop.dto.VideoMode;

class ShopProgressTest {

    private static ShopItem priced(long id) {
        return new ShopItem(
                id,
                "P" + id,
                ShopCategory.TIMER_BACKGROUND,
                null,
                null,
                null,
                "상품",
                null,
                null,
                1,
                100,
                null,
                null,
                null,
                null,
                null,
                null,
                true,
                null,
                null,
                null);
    }

    private static ShopItem reward(long id, UnlockType type, int value) {
        return new ShopItem(
                id,
                "R" + id,
                ShopCategory.TIMER_BACKGROUND,
                null,
                null,
                null,
                "보상",
                null,
                null,
                1,
                null,
                type,
                value,
                null,
                null,
                null,
                null,
                true,
                null,
                null,
                null);
    }

    private static ShopItem free(long id, boolean active) {
        return new ShopItem(
                id,
                "F" + id,
                ShopCategory.TIMER_BACKGROUND,
                null,
                null,
                null,
                "무료",
                null,
                null,
                1,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                active,
                "watcher/9c8d9f19",
                AssetType.VIDEO,
                VideoMode.LOOP);
    }

    @Test
    void 판매_중인_무료_상품은_누구나_가졌고_판매를_멈추면_아무도_갖지_않는다() {
        ShopProgress progress = new ShopProgress(Set.of(), 0, 0);

        assertThat(progress.owns(free(1, true))).isTrue();
        assertThat(progress.owns(free(2, false))).isFalse();
        assertThat(progress.unlockOf(free(1, true))).isNull();
        // 팔던 상품을 무료로 바꿔 구매 기록이 남아 있어도 판매를 멈추면 갖지 않은 것이다
        assertThat(new ShopProgress(Set.of(3L), 0, 0).owns(free(3, false))).isFalse();
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
