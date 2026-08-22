package project.study.user.service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import project.study.metrics.dto.NewUser;
import project.study.user.dto.UserRegisterRequest;
import project.study.user.dto.UserRegisterResponse;
import project.study.user.entity.Provider;
import project.study.user.entity.User;
import project.study.user.repository.UserRepository;

@Service
@RequiredArgsConstructor
public class UserService {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

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

    @Transactional(readOnly = true)
    public long countTotal() {
        return userRepository.count();
    }

    /**
     * 해당 날짜(KST)에 가입한 유저 목록 — 가입 시각 오름차순.
     *
     * <p>가입 수는 현재 실제보다 크다 — 구버전 앱 빌드가 첫 실행에서 서로 다른 UUID로 등록을
     * 두 번 호출해 유저가 2건씩 생긴다. 어느 쪽이 중복인지 서버가 판별할 수 없어 보정하지 않는다
     * (설계 문서의 "알려진 한계" 참고). 수정된 빌드가 퍼지면 자연히 정확해진다.
     */
    @Transactional(readOnly = true)
    public List<NewUser> findRegisteredOn(LocalDate date) {
        Instant from = date.atStartOfDay(KST).toInstant();
        Instant to = date.plusDays(1).atStartOfDay(KST).toInstant();
        return userRepository.findByCreatedAtGreaterThanEqualAndCreatedAtLessThanOrderByCreatedAtAsc(from, to).stream()
                .map(user -> new NewUser(user.getId(), user.getCreatedAt()))
                .toList();
    }
}
