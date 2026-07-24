package project.study.user.service;

import java.util.Locale;
import java.util.Optional;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import project.study.user.dto.UserRegisterRequest;
import project.study.user.dto.UserRegisterResponse;
import project.study.user.entity.Provider;
import project.study.user.entity.User;
import project.study.user.repository.UserRepository;

@Service
public class UserService {

    private final UserRepository userRepository;
    private final TransactionTemplate transactionTemplate;

    public UserService(UserRepository userRepository, PlatformTransactionManager transactionManager) {
        this.userRepository = userRepository;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    public UserRegisterResponse register(UserRegisterRequest request) {
        // 플랫폼마다 UUID 대소문자 표기가 달라 같은 기기가 유저를 중복 생성하지 않도록 정규화
        String deviceId = request.deviceId().toLowerCase(Locale.ROOT);
        try {
            return transactionTemplate.execute(status -> registerTransaction(deviceId));
        } catch (DataIntegrityViolationException e) {
            // 같은 기기의 동시 첫 등록 경쟁에서 패배 → 상대가 만든 유저를 새 트랜잭션에서 조회해 재시도
            // (트랜잭션 안에서 catch하면 rollback-only 때문에 실패하므로 반드시 실행 단위를 분리)
            return transactionTemplate.execute(status -> registerTransaction(deviceId));
        }
    }

    private UserRegisterResponse registerTransaction(String deviceId) {
        Optional<User> existing = userRepository.findByProviderAndProviderUserId(Provider.DEVICE, deviceId);
        boolean isNew = existing.isEmpty();
        User user = existing.orElseGet(() -> userRepository.save(new User(Provider.DEVICE, deviceId)));
        return new UserRegisterResponse(user.getId(), isNew);
    }
}
