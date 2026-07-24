package project.study.user.service;

import java.util.Locale;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import project.study.user.dto.UserRegisterRequest;
import project.study.user.dto.UserRegisterResponse;
import project.study.user.entity.Provider;
import project.study.user.entity.User;
import project.study.user.repository.UserRepository;

@Service
@RequiredArgsConstructor
public class UserService {

    private final UserRepository userRepository;

    // @Modifying 네이티브 쿼리는 트랜잭션 안에서만 실행할 수 있다
    @Transactional
    public UserRegisterResponse register(UserRegisterRequest request) {
        // 플랫폼마다 UUID 대소문자 표기가 달라 같은 기기가 유저를 중복 생성하지 않도록 정규화
        String deviceId = request.deviceId().toLowerCase(Locale.ROOT);

        // on conflict do nothing이라 이미 있으면 0행 — 동시 첫 등록 경쟁도 DB가 정리한다
        boolean isNew = userRepository.insertIfAbsent(Provider.DEVICE.name(), deviceId) > 0;
        User user = userRepository
                .findByProviderAndProviderUserId(Provider.DEVICE, deviceId)
                .orElseThrow(() -> new IllegalStateException("등록 이후 조회 실패"));

        return new UserRegisterResponse(user.getId(), isNew);
    }
}
