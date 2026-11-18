package project.study.shop.service;

import java.util.Set;
import project.study.shop.dto.ShopItem;
import project.study.shop.dto.ShopItemUnlock;

/** 한 사용자가 가진 상품과 보상 조건의 진행. 요청 하나에서 한 번 읽어 여러 상품에 쓴다. */
record ShopProgress(Set<Long> purchasedItemIds, long totalFocusSec, int maxStreakDays) {

    private static final int SECONDS_PER_HOUR = 3600;

    /**
     * 산 상품, 조건을 채운 보상 상품, 판매 중인 무료 상품이면 가진 것이다.
     *
     * 무료 상품을 판매를 멈춘 뒤에도 가진 것으로 보면 모두가 가진 셈이라 목록에서 내릴 수 없다.
     * 그래서 무료 상품은 구매 기록이 남아 있어도 판매 여부만 본다.
     */
    boolean owns(ShopItem item) {
        if (item.isFree()) {
            return item.active();
        }
        if (purchasedItemIds.contains(item.id())) {
            return true;
        }
        return item.isReward() && currentValue(item) >= item.unlockValue();
    }

    ShopItemUnlock unlockOf(ShopItem item) {
        return item.isReward() ? new ShopItemUnlock(item.unlockType(), item.unlockValue(), currentValue(item)) : null;
    }

    private long currentValue(ShopItem item) {
        return switch (item.unlockType()) {
            case STREAK_DAYS -> maxStreakDays;
            case TOTAL_FOCUS_HOURS -> totalFocusSec / SECONDS_PER_HOUR;
        };
    }
}
