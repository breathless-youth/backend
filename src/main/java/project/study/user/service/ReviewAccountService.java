package project.study.user.service;

import java.util.concurrent.ThreadLocalRandom;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import project.study.point.dto.PointReason;
import project.study.point.service.PointService;
import project.study.shop.service.ShopGrantService;
import project.study.user.entity.Provider;
import project.study.user.repository.ReviewLoginRepository.Settings;
import project.study.user.repository.UserRepository;

/**
 * 심사 계정 (BY-1300). 계정은 하나이고 없으면 만든다 — 심사자가 계정 삭제를 시험한 뒤 다시 로그인하면 새로 만들어 처음 상태(포인트와 상품
 * 몇 개)로 채운다. 심사자 여럿이 동시에 쓸 수 있어 로그인할 때 다른 기기의 토큰을 지우지 않는다.
 */
@Service
@RequiredArgsConstructor
public class ReviewAccountService {

    static final String PROVIDER_USER_ID = "review";
    private static final String NICKNAME = "Reviewer";
    private static final String INITIAL_GRANT = "review:init";
    private static final int NICKNAME_ATTEMPTS = 10;
    private static final int COLORS = 8;

    private final UserRepository userRepository;
    private final TokenIssuer tokenIssuer;
    private final PointService pointService;
    private final ShopGrantService shopGrants;

    @Transactional
    public AuthService.TokenPair signIn(Settings settings) {
        boolean created = false;
        for (int attempt = 0; attempt < NICKNAME_ATTEMPTS && !created && findId() == null; attempt++) {
            // 닉네임이 겹치면 0행이다. 숫자를 붙여 다시 넣는다
            String nickname = attempt == 0
                    ? NICKNAME
                    : NICKNAME + ThreadLocalRandom.current().nextInt(1000, 10000);
            created = userRepository.insertIfAbsent(
                            Provider.REVIEW.name(),
                            PROVIDER_USER_ID,
                            nickname,
                            "R",
                            ThreadLocalRandom.current().nextInt(COLORS))
                    == 1;
        }
        Long userId = findId();
        if (userId == null) {
            throw new IllegalStateException("심사 계정을 만들지 못했습니다");
        }
        // 토큰 발급은 사용자 행을 잠근 채 한다 (TokenIssuer)
        userRepository.findByIdForUpdate(userId).orElseThrow();
        if (created) {
            if (settings.initialPoints() > 0) {
                pointService.earn(userId, PointReason.GRANT, INITIAL_GRANT, settings.initialPoints(), null, null);
            }
            shopGrants.grantByCodes(userId, settings.itemCodes());
        }
        return tokenIssuer.issue(userId);
    }

    private Long findId() {
        return userRepository
                .findIdByProviderAndProviderUserId(Provider.REVIEW, PROVIDER_USER_ID)
                .orElse(null);
    }
}
