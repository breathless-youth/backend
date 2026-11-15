package project.study.shop.service;

import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import project.study.shop.dto.ShopItem;
import project.study.shop.repository.ShopRepository;

/**
 * 스토어 결제로 사는 상품의 보유를 주고 거둔다 (BY-1292). 결제 알림을 처리하는 쪽이 부르고, 부르기 전에 그 사용자의 포인트 사용자 락을 잡아
 * 꾸미기 저장·탈퇴와 겹치지 않게 한다.
 */
@Service
@RequiredArgsConstructor
public class PaidItemService {

    private final ShopRepository repository;

    @Transactional(readOnly = true)
    public Optional<ShopItem> findByStoreProductId(String storeProductId) {
        return repository.findByStoreProductId(storeProductId);
    }

    @Transactional
    public void grant(long userId, long itemId) {
        repository.insertOwnedIfAbsent(userId, itemId);
    }

    /** 환불된 상품을 거둔다. 끼고 있거나 배경으로 쓰고 있으면 바로 푼다. */
    @Transactional
    public void revoke(long userId, long itemId) {
        repository.deleteOwned(userId, itemId);
    }
}
