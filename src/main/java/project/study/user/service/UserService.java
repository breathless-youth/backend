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
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import project.study.common.exception.BadRequestException;
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
import project.study.user.nickname.AutoNicknames;
import project.study.user.nickname.NicknameBlocklist;
import project.study.user.nickname.NicknameLanguage;
import project.study.user.nickname.Nicknames;
import project.study.user.repository.UserRepository;

@Slf4j
@Service
@RequiredArgsConstructor
public class UserService {

    private static final SecureRandom RANDOM = new SecureRandom();
    // 조합 닉네임은 1만 개라 자동 닉네임 유저가 수천 명을 넘으면 충돌이 잦아진다. 이만큼 연달아 막히면 옛 방식(10만 개)으로 넘어간다
    private static final int AUTO_NICKNAME_MAX_ATTEMPTS = 10;
    private static final int COLOR_COUNT = 8;

    private final UserRepository userRepository;
    private final ProfileImageUrls profileImageUrls;

    // @Modifying 네이티브 쿼리는 트랜잭션 안에서만 실행할 수 있다
    @Transactional
    public RegisterResult register(UserRegisterRequest request) {
        // 플랫폼마다 UUID 대소문자 표기가 달라 같은 기기가 유저를 중복 생성하지 않도록 정규화
        String deviceId = request.deviceId().toLowerCase(Locale.ROOT);

        Optional<User> existing = insertWithAutoNickname(deviceId, NicknameLanguage.fromTag(request.language()));
        User user = existing.orElseGet(() -> userRepository
                .findByProviderAndProviderUserId(Provider.DEVICE, deviceId)
                .orElseThrow(() -> new IllegalStateException("등록 이후 조회 실패")));

        return new RegisterResult(user.getId(), existing.isEmpty(), deviceId);
    }

    /**
     * 등록 결과. deviceId는 정규화한 값이다. 토큰은 {@link DeviceRegistrationService}가 덧붙인다 — 시더 같은 내부 호출은 토큰이 필요
     * 없다.
     */
    public record RegisterResult(Long userId, boolean isNew, String deviceId) {}

    // 자동 닉네임(휴대폰 언어 목록의 꾸미는 말 + 동물)이 기존 닉네임과 충돌하면 다시 뽑는다. 조합이 연달아 막히면 옛 방식(포메{랜덤5자리})으로 넘어간다 (BY-1135)
    private Optional<User> insertWithAutoNickname(String deviceId, NicknameLanguage language) {
        for (int attempt = 0; attempt < AUTO_NICKNAME_MAX_ATTEMPTS * 2; attempt++) {
            if (attempt == AUTO_NICKNAME_MAX_ATTEMPTS) {
                log.warn("자동 닉네임 조합이 {}번 연달아 충돌해 옛 방식으로 발급한다 — 단어 목록을 늘릴 때다", AUTO_NICKNAME_MAX_ATTEMPTS);
            }
            String nickname = attempt < AUTO_NICKNAME_MAX_ATTEMPTS
                    ? AutoNicknames.random(language, RANDOM)
                    : "포메" + String.format("%05d", RANDOM.nextInt(100000));
            int inserted = userRepository.insertIfAbsent(
                    Provider.DEVICE.name(),
                    deviceId,
                    nickname,
                    Nicknames.initialOf(nickname),
                    RANDOM.nextInt(COLOR_COUNT));
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

    /**
     * 사용자가 있는지 확인한다 — 없으면 404 {@code USER_NOT_FOUND}. users를 FK로 가리키지 않는 테이블에 쓰기 전에 부른다: 탈퇴 뒤 남은 옛
     * access 토큰의 쓰기가 지워진 사람의 행을 다시 만들지 않게 한다 (ADR-0043).
     */
    @Transactional(readOnly = true)
    public void requireExists(Long userId) {
        if (!userRepository.existsById(userId)) {
            throw new NotFoundException(ErrorCode.USER_NOT_FOUND, "존재하지 않는 사용자입니다");
        }
    }

    @Transactional(readOnly = true)
    public ProfileResponse getProfile(Long userId) {
        User user = findUser(userId);
        return toProfileResponse(user);
    }

    @Transactional
    public ProfileResponse updateProfile(Long userId, ProfileUpdateRequest request) {
        User user = findUser(userId);

        // 검증기와 같은 정규화(NFKC + 앞뒤 공백 제거)를 저장 직전에 적용한다 (BY-647, BY-1135)
        String nickname = Nicknames.normalize(request.nickname());
        // 바꾸려는 닉네임만 금칙어를 본다 — 지금 닉네임을 그대로 다시 보내는 요청(앱이 폼 전체를 보냄)은 막지 않는다
        if (nickname != null && !nickname.equals(user.getNickname()) && NicknameBlocklist.isBlocked(nickname)) {
            throw new BadRequestException(ErrorCode.NICKNAME_NOT_ALLOWED, "쓸 수 없는 닉네임이에요");
        }
        user.updateProfile(nickname, request.goal(), request.category());
        UserTimeZones.parse(request.timeZone()).ifPresent(user::changeTimeZone);
        try {
            // 닉네임 유니크 제약이 최종 판정이다 — 커밋 시점의 위반이 500으로 새지 않도록 여기서 flush해 409로 변환한다
            userRepository.flush();
        } catch (DataIntegrityViolationException e) {
            throw new ConflictException("이미 사용 중인 닉네임입니다(영문 대소문자만 다른 닉네임도 같은 닉네임이에요)");
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
                profileImageUrls.of(user.getProfileImage()),
                user.getProvider() == Provider.DEVICE
                        ? null
                        : user.getProvider().name());
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
