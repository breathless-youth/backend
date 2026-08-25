package project.study.user.service;

import java.security.SecureRandom;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Locale;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import project.study.common.BadRequestException;
import project.study.common.ConflictException;
import project.study.common.ErrorCode;
import project.study.common.NotFoundException;
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
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int AUTO_NICKNAME_MAX_ATTEMPTS = 10;
    private static final int COLOR_COUNT = 8;
    private static final int NICKNAME_MIN_LENGTH = 2;
    private static final int NICKNAME_MAX_LENGTH = 12;
    private static final int GOAL_MAX_LENGTH = 20;
    private static final String NICKNAME_PATTERN = "[가-힣a-zA-Z0-9]+";
    private static final Set<String> CATEGORIES =
            Set.of("PROFESSIONAL", "CSAT", "JOB", "CERTIFICATE", "CIVIL_SERVICE", "LANGUAGE", "ETC");

    private final UserRepository userRepository;

    // @Modifying 네이티브 쿼리는 트랜잭션 안에서만 실행할 수 있다
    @Transactional
    public UserRegisterResponse register(UserRegisterRequest request) {
        // 플랫폼마다 UUID 대소문자 표기가 달라 같은 기기가 유저를 중복 생성하지 않도록 정규화
        String deviceId = request.deviceId().toLowerCase(Locale.ROOT);

        boolean isNew = insertWithAutoNickname(deviceId);
        User user = userRepository
                .findByProviderAndProviderUserId(Provider.DEVICE, deviceId)
                .orElseThrow(() -> new IllegalStateException("등록 이후 조회 실패"));

        return new UserRegisterResponse(user.getId(), isNew);
    }

    // 자동 닉네임(포메{랜덤5자리})이 기존 닉네임과 충돌하면 재생성해서 재시도한다.
    // insertIfAbsent는 타겟 없는 on conflict do nothing이라 어떤 유니크 충돌이든 0행으로 떨어진다 —
    // 0행일 때 기기(deviceId)가 이미 있으면 멱등 재등록, 없으면 닉네임 충돌이므로 재시도
    private boolean insertWithAutoNickname(String deviceId) {
        for (int attempt = 0; attempt < AUTO_NICKNAME_MAX_ATTEMPTS; attempt++) {
            String nickname = "포메" + String.format("%05d", RANDOM.nextInt(100000));
            int inserted = userRepository.insertIfAbsent(
                    Provider.DEVICE.name(), deviceId, nickname, "포", RANDOM.nextInt(COLOR_COUNT));
            if (inserted > 0) {
                return true;
            }
            if (userRepository
                    .findByProviderAndProviderUserId(Provider.DEVICE, deviceId)
                    .isPresent()) {
                return false;
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
        validateProfile(request);
        User user = findUser(userId);

        if (request.nickname() != null
                && !request.nickname().equals(user.getNickname())
                && userRepository.existsByNickname(request.nickname())) {
            throw new ConflictException("이미 사용 중인 닉네임입니다");
        }

        user.updateProfile(request.nickname(), request.goal(), request.category());
        try {
            // 위의 exists 검사는 동시 요청 사이에 낡을 수 있다 — 최종 판정은 유니크 제약이 하고,
            // 커밋 시점의 위반이 500으로 새지 않도록 여기서 flush해 409로 변환한다
            userRepository.flush();
        } catch (org.springframework.dao.DataIntegrityViolationException e) {
            throw new ConflictException("이미 사용 중인 닉네임입니다");
        }
        return toProfileResponse(user);
    }

    private void validateProfile(ProfileUpdateRequest request) {
        String nickname = request.nickname();
        if (nickname != null
                && (nickname.length() < NICKNAME_MIN_LENGTH
                        || nickname.length() > NICKNAME_MAX_LENGTH
                        || !nickname.matches(NICKNAME_PATTERN))) {
            throw new BadRequestException("닉네임은 2~12자의 한글·영문·숫자만 사용할 수 있습니다");
        }
        if (request.goal() != null && request.goal().length() > GOAL_MAX_LENGTH) {
            throw new BadRequestException("목표는 공백 포함 20자 이하여야 합니다");
        }
        if (request.category() != null && !CATEGORIES.contains(request.category())) {
            throw new BadRequestException("정의되지 않은 카테고리입니다");
        }
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
}
