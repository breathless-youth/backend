package project.study.user.service;

import java.security.SecureRandom;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import project.study.common.exception.ConflictException;
import project.study.common.exception.ErrorCode;
import project.study.common.exception.NotFoundException;
import project.study.metrics.dto.NewUser;
import project.study.user.dto.ProfileResponse;
import project.study.user.dto.ProfileUpdateRequest;
import project.study.user.dto.UserRegisterRequest;
import project.study.user.dto.UserRegisterResponse;
import project.study.user.entity.Provider;
import project.study.user.entity.User;
import project.study.user.repository.UserRepository;

@Service
@RequiredArgsConstructor
public class UserService {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final DateTimeFormatter HOUR_MINUTE =
            DateTimeFormatter.ofPattern("HH:mm").withZone(KST);
    private static final SecureRandom RANDOM = new SecureRandom();
    // 자동 닉네임 공간이 10만 개(5자리)라 자동 닉네임 유저가 수만 명을 넘으면 재시도 횟수보다 공간이 병목이 된다
    private static final int AUTO_NICKNAME_MAX_ATTEMPTS = 10;
    private static final int COLOR_COUNT = 8;

    private final UserRepository userRepository;

    // @Modifying 네이티브 쿼리는 트랜잭션 안에서만 실행할 수 있다
    @Transactional
    public UserRegisterResponse register(UserRegisterRequest request) {
        // 플랫폼마다 UUID 대소문자 표기가 달라 같은 기기가 유저를 중복 생성하지 않도록 정규화
        String deviceId = request.deviceId().toLowerCase(Locale.ROOT);

        Optional<User> existing = insertWithAutoNickname(deviceId);
        User user = existing.orElseGet(() -> userRepository
                .findByProviderAndProviderUserId(Provider.DEVICE, deviceId)
                .orElseThrow(() -> new IllegalStateException("등록 이후 조회 실패")));

        return new UserRegisterResponse(user.getId(), existing.isEmpty());
    }

    // 자동 닉네임(포메{랜덤5자리})이 기존 닉네임과 충돌하면 재생성해서 재시도한다.
    private Optional<User> insertWithAutoNickname(String deviceId) {
        for (int attempt = 0; attempt < AUTO_NICKNAME_MAX_ATTEMPTS; attempt++) {
            String nickname = "포메" + String.format("%05d", RANDOM.nextInt(100000));
            int inserted = userRepository.insertIfAbsent(
                    Provider.DEVICE.name(), deviceId, nickname, "포", RANDOM.nextInt(COLOR_COUNT));
            if (inserted > 0) {
                return Optional.empty();
            }
            Optional<User> existing = userRepository.findByProviderAndProviderUserId(Provider.DEVICE, deviceId);
            if (existing.isPresent()) {
                return existing;
            }
        }
        throw new IllegalStateException("자동 닉네임 발급에 실패했습니다");
    }

    @Transactional(readOnly = true)
    public ProfileResponse getProfile(Long userId) {
        User user = findUser(userId);
        return toProfileResponse(user);
    }

    @Transactional
    public ProfileResponse updateProfile(Long userId, ProfileUpdateRequest request) {
        User user = findUser(userId);

        user.updateProfile(request.nickname(), request.goal(), request.category());
        try {
            // 닉네임 유니크 제약이 최종 판정이다 — 커밋 시점의 위반이 500으로 새지 않도록 여기서 flush해 409로 변환한다
            userRepository.flush();
        } catch (DataIntegrityViolationException e) {
            throw new ConflictException("이미 사용 중인 닉네임입니다");
        }
        return toProfileResponse(user);
    }

    private User findUser(Long userId) {
        return userRepository
                .findById(userId)
                .orElseThrow(() -> new NotFoundException(ErrorCode.USER_NOT_FOUND, "존재하지 않는 사용자입니다"));
    }

    private static ProfileResponse toProfileResponse(User user) {
        return new ProfileResponse(
                user.getNickname(), user.getGoal(), user.getCategory(), user.getInitial(), user.getColorIndex());
    }

    @Transactional(readOnly = true)
    public long countTotal() {
        return userRepository.count();
    }

    /**
     * 해당 날짜(KST)에 가입한 유저 수.
     */
    @Transactional(readOnly = true)
    public long countRegisteredOn(LocalDate date) {
        Instant from = date.atStartOfDay(KST).toInstant();
        Instant to = date.plusDays(1).atStartOfDay(KST).toInstant();
        return userRepository.countByCreatedAtGreaterThanEqualAndCreatedAtLessThan(from, to);
    }

    /**
     * 해당 날짜(KST)에 가입한 유저 목록 — 일일 리포트의 신규 가입 상세용. 가입 시각 오름차순,
     * 시각은 KST "HH:mm"으로 포맷한다.
     */
    @Transactional(readOnly = true)
    public List<NewUser> findNewUsersOn(LocalDate date) {
        Instant from = date.atStartOfDay(KST).toInstant();
        Instant to = date.plusDays(1).atStartOfDay(KST).toInstant();
        return userRepository.findByCreatedAtGreaterThanEqualAndCreatedAtLessThanOrderByCreatedAt(from, to).stream()
                .map(user -> new NewUser(user.getId(), HOUR_MINUTE.format(user.getCreatedAt())))
                .toList();
    }
}
