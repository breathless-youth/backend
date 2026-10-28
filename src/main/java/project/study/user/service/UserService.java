package project.study.user.service;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import project.study.common.exception.ConflictException;
import project.study.common.exception.ErrorCode;
import project.study.common.exception.NotFoundException;
import project.study.metrics.report.fact.UserFact;
import project.study.user.dto.ProfileResponse;
import project.study.user.dto.ProfileUpdateRequest;
import project.study.user.dto.UserRegisterRequest;
import project.study.user.entity.Provider;
import project.study.user.entity.User;
import project.study.user.image.ProfileImageUrls;
import project.study.user.nickname.Nicknames;
import project.study.user.repository.UserRepository;

@Service
@RequiredArgsConstructor
public class UserService {

    private static final SecureRandom RANDOM = new SecureRandom();
    // 자동 닉네임 공간이 10만 개(5자리)라 자동 닉네임 유저가 수만 명을 넘으면 재시도 횟수보다 공간이 병목이 된다
    private static final int AUTO_NICKNAME_MAX_ATTEMPTS = 10;
    private static final int COLOR_COUNT = 8;

    private final UserRepository userRepository;
    private final ProfileImageUrls profileImageUrls;

    // @Modifying 네이티브 쿼리는 트랜잭션 안에서만 실행할 수 있다
    @Transactional
    public RegisterResult register(UserRegisterRequest request) {
        // 플랫폼마다 UUID 대소문자 표기가 달라 같은 기기가 유저를 중복 생성하지 않도록 정규화
        String deviceId = request.deviceId().toLowerCase(Locale.ROOT);

        Optional<User> existing = insertWithAutoNickname(deviceId);
        User user = existing.orElseGet(() -> userRepository
                .findByProviderAndProviderUserId(Provider.DEVICE, deviceId)
                .orElseThrow(() -> new IllegalStateException("등록 이후 조회 실패")));

        return new RegisterResult(user.getId(), existing.isEmpty());
    }

    /** 등록 결과. 토큰은 컨트롤러가 AuthService로 덧붙인다 — 시더 같은 내부 호출은 토큰이 필요 없다. */
    public record RegisterResult(Long userId, boolean isNew) {}

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

        // 검증기와 같은 정규화(NFC + 앞뒤 공백 제거)를 저장 직전에 적용한다 (BY-647)
        user.updateProfile(Nicknames.normalize(request.nickname()), request.goal(), request.category());
        try {
            // 닉네임 유니크 제약이 최종 판정이다 — 커밋 시점의 위반이 500으로 새지 않도록 여기서 flush해 409로 변환한다
            userRepository.flush();
        } catch (DataIntegrityViolationException e) {
            throw new ConflictException("이미 사용 중인 닉네임입니다");
        }
        return toProfileResponse(user);
    }

    /** 사진 키를 바꾼 뒤의 프로필과 바뀌기 전 키. */
    public record ProfileImageChange(ProfileResponse profile, String previousKey) {}

    @Transactional
    public ProfileImageChange replaceProfileImage(Long userId, String key) {
        User user = findUser(userId);
        String previous = user.replaceProfileImage(key);
        return new ProfileImageChange(toProfileResponse(user), previous);
    }

    private User findUser(Long userId) {
        return userRepository
                .findById(userId)
                .orElseThrow(() -> new NotFoundException(ErrorCode.USER_NOT_FOUND, "존재하지 않는 사용자입니다"));
    }

    private ProfileResponse toProfileResponse(User user) {
        return new ProfileResponse(
                user.getNickname(),
                user.getGoal(),
                user.getCategory(),
                user.getInitial(),
                user.getColorIndex(),
                profileImageUrls.of(user.getProfileImage()));
    }

    /** 회원번호 중 users에 있는 것 — Amplitude에만 있는 id(타 환경·지운 행)를 리포트에서 거른다 (스펙 §7.4, BY-904). */
    @Transactional(readOnly = true)
    public Set<Long> findExistingUserIds(Collection<Long> userIds) {
        if (userIds.isEmpty()) {
            return Set.of();
        }
        return userRepository.findAllById(userIds).stream().map(User::getId).collect(Collectors.toUnmodifiableSet());
    }

    /**
     * 일일 리포트 v2 대상 유저 — {@code signedUpFrom} 이후 가입자와, 그 전에 가입했지만 {@code alsoUserIds}에 든 유저(기간 안에
     * 공부한 사람). 가입 시각이 없는 옛 행은 날짜를 매길 수 없어 뺀다 (BY-904).
     */
    @Transactional(readOnly = true)
    public List<UserFact> findReportUsers(Instant signedUpFrom, Collection<Long> alsoUserIds) {
        Map<Long, User> users = new LinkedHashMap<>();
        userRepository.findByCreatedAtGreaterThanEqual(signedUpFrom).forEach(user -> users.put(user.getId(), user));
        List<Long> rest = alsoUserIds.stream()
                .filter(id -> !users.containsKey(id))
                .distinct()
                .toList();
        userRepository.findAllById(rest).forEach(user -> users.put(user.getId(), user));
        return users.values().stream()
                .filter(user -> user.getCreatedAt() != null)
                .map(user -> new UserFact(
                        user.getId(), user.getNickname(), user.getCreatedAt(), user.getCategory(), user.getGoal()))
                .toList();
    }
}
