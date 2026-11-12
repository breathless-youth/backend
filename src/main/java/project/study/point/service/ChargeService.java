package project.study.point.service;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import project.study.common.exception.NotFoundException;
import project.study.point.dto.PointProduct;
import project.study.point.dto.PointProductsResponse;
import project.study.point.dto.PointPurchase;
import project.study.point.repository.PointChargeRepository;
import project.study.user.service.MemberGuard;

/**
 * 포인트 충전과 환불. 결제는 앱의 RevenueCat SDK가 하고, RevenueCat이 스토어 영수증을 확인한 뒤 서버로 알림(웹훅)을 보낸다. 서버는 그
 * 알림으로만 지급하고 되돌린다. 앱은 RevenueCat에 사용자 ID(토큰의 sub)로 로그인해야 알림에서 사용자를 찾을 수 있다.
 *
 * <p>알림은 같은 것이 다시 오거나 순서가 바뀔 수 있다. 결제 한 건은 (스토어, 거래 ID)로 한 번만 지급하고, 환불이 먼저 오면 환불된 결제로
 * 적어 두어 뒤에 온 지급을 막는다. 처리할 수 없는 알림도 200으로 답한다 — 다시 받아도 결과가 같다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChargeService {

    private static final String PURCHASE = "NON_RENEWING_PURCHASE";
    private static final String CANCELLATION = "CANCELLATION";
    /** 스토어가 환불해 준 취소. 구독 해지(UNSUBSCRIBE) 같은 다른 사유는 환불이 아니다. */
    private static final String REFUND_REASON = "CUSTOMER_SUPPORT";

    private static final int MAX_ID_LENGTH = 100;
    private static final int MAX_QUANTITY = 100;

    private final PointChargeRepository repository;
    private final PointService pointService;
    private final ChargePolicy policy;
    private final PointLocks locks;
    private final MemberGuard members;

    @Transactional(readOnly = true)
    public PointProductsResponse products(Long userId) {
        members.requireMember(userId);
        return new PointProductsResponse(repository.activeProducts());
    }

    /** Authorization 값이 설정과 같은지. 충전이 꺼진 서버면 404. */
    public boolean authorized(String authorizationHeader) {
        if (!policy.available()) {
            throw new NotFoundException("충전이 설정되지 않았습니다");
        }
        return policy.authorized(authorizationHeader);
    }

    /** event는 RevenueCat 웹훅 본문의 {@code event} 객체다. */
    @Transactional
    public void handle(Map<String, Object> event) {
        Notice notice = Notice.of(event);
        if (notice == null) {
            return;
        }
        if (notice.sandbox() && !policy.acceptSandbox()) {
            log.warn("샌드박스 결제 알림은 받지 않습니다: transaction_id={}", notice.transactionId());
            return;
        }
        Optional<PointProduct> product = repository.findProduct(notice.productId());
        if (product.isEmpty()) {
            // 구독 같은 다른 상품의 알림일 수 있다. 충전 상품인데 표에 없다면 돈을 받고 못 준 것이라 눈에 띄게 남긴다
            log.error(
                    "충전 상품 표에 없는 상품의 알림: product_id={} transaction_id={}", notice.productId(), notice.transactionId());
            return;
        }
        // 같은 결제의 지급과 환불을 한 줄로 세운다. 이 락 → 사용자 락 → 결제 행 순서로만 잡는다
        locks.lockPurchase(notice.store(), notice.transactionId());
        Long userId = memberOf(event);
        if (notice.refund()) {
            refund(notice, product.get(), userId);
        } else {
            grant(notice, product.get(), userId);
        }
    }

    private void grant(Notice notice, PointProduct product, Long userId) {
        if (userId == null) {
            log.error("사용자를 찾지 못한 충전 알림 — 돈을 받고 포인트를 주지 못했습니다: transaction_id={}", notice.transactionId());
            return;
        }
        int points = product.points() * notice.quantity();
        int bonus = product.bonusPoints() * notice.quantity();
        pointService.lock(userId);
        if (!repository.insert(notice.purchase(userId, product, points, bonus), false)) {
            return;
        }
        pointService.charge(userId, notice.refKey(), points, bonus, product.storeProductId());
    }

    private void refund(Notice notice, PointProduct product, Long eventUserId) {
        // 지급한 사용자를 알아야 락을 잡는다. 행이 있으면 그 사용자, 없으면 알림의 사용자다. 행은 락을 잡은 뒤에 잠근다
        Long owner =
                repository.findOwner(notice.store(), notice.transactionId()).orElse(eventUserId);
        if (owner != null) {
            pointService.lock(owner);
        }
        Optional<PointPurchase> found = repository.findForUpdate(notice.store(), notice.transactionId());
        if (found.isEmpty()) {
            // 지급 알림보다 먼저 왔다. 환불된 결제로 적어 두어 뒤에 오는 지급을 막는다
            repository.insert(notice.purchase(eventUserId, product, 0, 0), true);
            return;
        }
        PointPurchase purchase = found.get();
        if (purchase.refunded()) {
            return;
        }
        int total = purchase.points() + purchase.bonusPoints();
        int reclaimed = purchase.userId() == null
                ? 0
                : pointService.reclaim(
                        purchase.userId(),
                        notice.refKey(),
                        purchase.points(),
                        purchase.bonusPoints(),
                        purchase.storeProductId());
        repository.markRefunded(purchase.id(), reclaimed, total - reclaimed);
        if (reclaimed < total) {
            log.warn(
                    "환불된 결제의 포인트를 이미 써서 다 되돌리지 못했습니다: transaction_id={} 모자란 포인트={}",
                    notice.transactionId(),
                    total - reclaimed);
        }
    }

    /**
     * 알림이 가리키는 우리 회원. app_user_id(마지막으로 로그인한 ID)가 회원이면 그 사람이다. 로그인 전 익명 ID로 온 알림은 다른 이름들에서
     * 찾되, 서로 다른 회원이 둘 이상이면 누구의 결제인지 알 수 없어 주지 않는다.
     */
    private Long memberOf(Map<String, Object> event) {
        Long current = member(text(event, "app_user_id"));
        if (current != null) {
            return current;
        }
        Set<Long> others = new LinkedHashSet<>();
        addMember(others, text(event, "original_app_user_id"));
        if (event.get("aliases") instanceof List<?> aliases) {
            aliases.forEach(alias -> addMember(others, alias == null ? null : alias.toString()));
        }
        if (others.size() > 1) {
            log.error("알림의 사용자 ID에 회원이 여럿입니다: transaction_id={} 회원={}", text(event, "transaction_id"), others);
            return null;
        }
        return others.isEmpty() ? null : others.iterator().next();
    }

    private void addMember(Set<Long> into, String candidate) {
        Long userId = member(candidate);
        if (userId != null) {
            into.add(userId);
        }
    }

    /** 숫자이고 우리 회원이면 그 ID. $RCAnonymousID 같은 익명 ID나 비회원은 null. */
    private Long member(String candidate) {
        try {
            if (candidate != null) {
                long userId = Long.parseLong(candidate);
                return members.isMember(userId) ? userId : null;
            }
        } catch (NumberFormatException e) {
            // 익명 ID
        }
        return null;
    }

    /** 알림에서 읽은 결제 또는 환불 한 건. */
    private record Notice(
            boolean refund,
            String store,
            String transactionId,
            String productId,
            boolean sandbox,
            int quantity,
            Instant purchasedAt) {

        /** 결제도 환불도 아니거나 읽을 수 없으면 null. */
        static Notice of(Map<String, Object> event) {
            String type = text(event, "type");
            boolean refund = CANCELLATION.equals(type) && REFUND_REASON.equals(text(event, "cancel_reason"));
            if (!refund && !PURCHASE.equals(type)) {
                return null;
            }
            String store = text(event, "store");
            String transactionId = text(event, "transaction_id");
            String productId = text(event, "product_id");
            if (invalidId(store) || invalidId(transactionId) || invalidId(productId)) {
                log.error("읽을 수 없는 충전 알림: type={} id={}", type, text(event, "id"));
                return null;
            }
            return new Notice(
                    refund,
                    store,
                    transactionId,
                    productId,
                    !"PRODUCTION".equals(text(event, "environment")),
                    quantityOf(event),
                    purchasedAtOf(event));
        }

        String refKey() {
            return "rc:" + store + ":" + transactionId;
        }

        PointChargeRepository.NewPurchase purchase(Long userId, PointProduct product, int points, int bonus) {
            return new PointChargeRepository.NewPurchase(
                    userId, store, transactionId, product.storeProductId(), points, bonus, sandbox, purchasedAt);
        }
    }

    private static boolean invalidId(String value) {
        return value == null || value.isBlank() || value.length() > MAX_ID_LENGTH;
    }

    /** 한 번에 여러 개를 산 결제면 그 개수. 없거나 이상한 값이면 1. */
    private static int quantityOf(Map<String, Object> event) {
        return event.get("quantity") instanceof Number number && number.intValue() >= 1
                ? Math.min(number.intValue(), MAX_QUANTITY)
                : 1;
    }

    private static Instant purchasedAtOf(Map<String, Object> event) {
        return event.get("purchased_at_ms") instanceof Number number ? Instant.ofEpochMilli(number.longValue()) : null;
    }

    private static String text(Map<String, Object> event, String key) {
        Object value = event.get(key);
        return value == null ? null : value.toString();
    }
}
