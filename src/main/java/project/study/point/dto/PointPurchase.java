package project.study.point.dto;

/**
 * 스토어 결제 한 건의 저장된 모습. userId는 탈퇴했거나 사용자를 찾지 못한 환불 표시면 null이다. itemId는 상점 상품을 산 결제면 그 상품이고
 * 포인트 충전이면 null이다.
 */
public record PointPurchase(
        long id, Long userId, String storeProductId, int points, int bonusPoints, boolean refunded, Long itemId) {}
