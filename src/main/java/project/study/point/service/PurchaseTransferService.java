package project.study.point.service;

import java.util.Set;
import java.util.TreeSet;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import project.study.point.repository.PointChargeRepository;
import project.study.shop.service.PaidItemService;

/**
 * 구매 복원 (BY-1297). 앱이 RevenueCat의 구매 복원을 부르면 RevenueCat이 결제를 지금 로그인한 사용자로 옮기고 {@code TRANSFER}를
 * 알린다. 알림에는 옮겨 준 사용자와 받은 사용자만 있고 결제 하나하나는 없어서, 옮겨 준 회원이 그 스토어에서 결제로 산 상점 상품을 모두 받은
 * 회원에게 옮긴다. 포인트 충전은 이미 쓴 것일 수 있어 옮기지 않는다.
 *
 * <p>알림 ID를 적어 같은 알림은 한 번만 처리한다. 옮긴 뒤의 환불은 결제 행의 회원(받은 회원)에게서 거둔다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PurchaseTransferService {

    private final PointChargeRepository repository;
    private final PointLocks locks;
    private final PaidItemService paidItems;

    /** from·to는 알림의 사용자 ID 가운데 우리 회원인 것. */
    @Transactional
    public void transfer(String eventId, Set<Long> from, Set<Long> to, String store, boolean sandbox) {
        if (to.size() != 1) {
            // 받는 쪽이 익명 ID뿐이면 로그인한 뒤의 알림이 다시 온다. 회원이 둘 이상이면 누구에게 줄지 알 수 없다
            log.warn("구매 복원 알림에서 받을 회원을 하나로 정하지 못했습니다: id={} 회원={}", eventId, to);
            return;
        }
        if (store == null || store.isBlank()) {
            // 스토어를 모르면 어느 결제가 옮겨졌는지 좁힐 수 없다. 다른 스토어의 구매까지 옮기지 않도록 사람이 보게 남긴다
            log.error("스토어가 없는 구매 복원 알림은 옮기지 않습니다: id={}", eventId);
            return;
        }
        long receiver = to.iterator().next();
        Set<Long> givers = new TreeSet<>(from);
        givers.remove(receiver);
        if (givers.isEmpty()) {
            return;
        }
        // 사용자 락을 여럿 잡는 곳은 여기뿐이다. 늘 번호가 작은 쪽부터 잡는다
        Set<Long> all = new TreeSet<>(givers);
        all.add(receiver);
        all.forEach(locks::lockUser);
        if (!repository.insertTransferEvent(eventId)) {
            return;
        }
        for (long giver : givers) {
            for (long itemId : repository.moveItemPurchases(giver, receiver, store, sandbox)) {
                if (!repository.hasOtherGrantedItem(giver, itemId, -1)) {
                    paidItems.revoke(giver, itemId);
                }
                paidItems.grant(receiver, itemId);
                log.info("구매 복원으로 상품 보유를 옮겼습니다: item={} from={} to={}", itemId, giver, receiver);
            }
        }
    }
}
