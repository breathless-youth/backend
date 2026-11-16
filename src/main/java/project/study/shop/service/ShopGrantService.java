package project.study.shop.service;

import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import project.study.shop.repository.ShopRepository;

/** 운영이 상품을 그냥 준다. 심사 계정의 처음 상태에 쓴다 (BY-1300). 포인트는 오가지 않는다. */
@Slf4j
@Service
@RequiredArgsConstructor
public class ShopGrantService {

    private final ShopRepository repository;

    /** 없는 코드는 건너뛴다. */
    @Transactional
    public void grantByCodes(long userId, List<String> codes) {
        for (String code : codes) {
            repository
                    .findIdByCode(code)
                    .ifPresentOrElse(
                            itemId -> repository.insertOwnedIfAbsent(userId, itemId),
                            () -> log.warn("주려는 상점 상품이 없습니다: code={}", code));
        }
    }
}
