package project.study.shop.dto;

import java.util.List;

/**
 * shop_item 테이블 한 행. 얻는 방법은 셋 중 하나다: price가 있으면 포인트로 사고, unlockType·unlockValue가 있으면 조건으로 받고,
 * storeProductId가 있으면 스토어 결제로 산다.
 * 타이머 배경은 셋 다 없는 무료 상품도 된다.
 */
public record ShopItem(
        long id,
        String code,
        ShopCategory category,
        String mascotCode,
        AccessorySlot slot,
        String tag,
        String name,
        String imageUrl,
        String backImageUrl,
        int grade,
        Integer price,
        UnlockType unlockType,
        Integer unlockValue,
        String storeProductId,
        String effectImageUrl,
        List<EffectPiece> effectPieces,
        String effectType,
        boolean active,
        String assetKey,
        AssetType assetType) {

    /** 조건을 채우면 받는 보상 상품인지. */
    public boolean isReward() {
        return unlockType != null;
    }

    /** 얻는 방법 없이 모두가 가지는 무료 상품인지. */
    public boolean isFree() {
        return price == null && unlockType == null && storeProductId == null;
    }

    /** 포인트로 사는 상품인지. */
    public boolean isPriced() {
        return price != null;
    }
}
