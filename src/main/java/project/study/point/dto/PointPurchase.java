package project.study.point.dto;

/** 스토어 결제 한 건의 저장된 모습. userId는 탈퇴했거나 사용자를 찾지 못한 환불 표시면 null이다. */
public record PointPurchase(
        long id, Long userId, String storeProductId, int points, int bonusPoints, boolean refunded) {}
