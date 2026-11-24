package project.study.premiumwaitlist.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import project.study.premiumwaitlist.dto.PremiumWaitlistRequest;
import project.study.premiumwaitlist.repository.PremiumWaitlistRepository;
import project.study.user.service.UserService;

@Service
@RequiredArgsConstructor
public class PremiumWaitlistService {

    private final PremiumWaitlistRepository repository;
    private final UserService userService;

    /** 사용자당 한 행으로 덮어쓴다. 탈퇴 뒤 남은 옛 토큰이 FK 위반(500)이 아니라 404가 되도록 사용자부터 확인한다. */
    @Transactional
    public void apply(Long userId, PremiumWaitlistRequest request) {
        userService.requireExists(userId);
        repository.upsert(
                userId,
                request.price(),
                request.placement().name(),
                request.ageOver14(),
                request.emailToStore(),
                request.expectedFeature() == null
                        ? null
                        : request.expectedFeature().name());
    }
}
