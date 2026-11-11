package project.study.shop.dto;

/** shop_item 테이블 한 행. price가 있으면 포인트로 사는 상품, 없으면 unlockType·unlockValue 조건으로 받는 보상 상품이다. */
public record ShopItem(
        long id,
        String code,
        ShopCategory category,
        String mascotCode,
        AccessorySlot slot,
        String tag,
        String name,
        String imageUrl,
        Integer price,
        UnlockType unlockType,
        Integer unlockValue) {

    public boolean isReward() {
        return price == null;
    }
}
