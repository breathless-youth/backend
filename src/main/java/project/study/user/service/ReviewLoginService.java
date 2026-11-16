package project.study.user.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import project.study.common.exception.BadRequestException;
import project.study.common.exception.ErrorCode;
import project.study.common.exception.ForbiddenException;
import project.study.common.exception.TooManyRequestsException;
import project.study.user.dto.ReviewLoginRequest;
import project.study.user.repository.ReviewLoginRepository;
import project.study.user.repository.ReviewLoginRepository.Settings;

/**
 * 스토어 심사용 로그인 (BY-1300, ADR-0048). 심사 계정으로 Google 계정을 주면 낯선 기기 본인 확인에 막혀, 심사 계정 하나만 아이디·
 * 비밀번호로 들어오는 길을 둔다.
 *
 * <p>켜짐: 설정 행이 있고 앱 버전이 출시된 버전보다 높을 때다. 앱 버전은 누구나 꾸밀 수 있어 입구를 숨기는 용도일 뿐이고, 막는 것은 비밀번호
 * (BCrypt 해시)와 시도 제한이다. 제한은 IP별이다 — 계정 단위로 잠그면 남이 일부러 틀려 심사자를 막을 수 있다. 하루 전체 상한을 넘으면 그날은
 * 닫는다.
 *
 * <p>틀린 시도를 적는 것이 롤백되지 않게 이 클래스에는 트랜잭션을 걸지 않는다. 계정을 만들고 토큰을 주는 부분만
 * {@link ReviewAccountService}의 트랜잭션이다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReviewLoginService {

    private static final int MAX_PASSWORD_LENGTH = 128;
    private static final int CLIENT_LIMIT = 5;
    private static final Duration CLIENT_WINDOW = Duration.ofMinutes(10);
    private static final int DAILY_LIMIT = 200;
    private static final Duration DAY = Duration.ofDays(1);
    // 설정이 없을 때도 같은 시간을 쓰도록 비교할 해시 ("unused"의 BCrypt)
    private static final String DUMMY_HASH = "$2a$10$7EqJtq98hPqEX7fNZaFWoOa5Yb1mZp0eSK2pZ7R9G2Yx1v0tW3h7e";

    private final ReviewLoginRepository repository;
    private final ReviewAccountService accounts;
    private final Clock clock;
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    public boolean enabled(String appVersion) {
        return enabled(repository.find(), appVersion);
    }

    public AuthService.TokenPair login(ReviewLoginRequest request, String client) {
        Optional<Settings> settings = repository.find();
        if (!enabled(settings, request.appVersion())) {
            throw new ForbiddenException(ErrorCode.REVIEW_LOGIN_DISABLED, "심사용 로그인을 쓸 수 없습니다");
        }
        Instant now = clock.instant();
        if (repository.failuresOf(client, now.minus(CLIENT_WINDOW)) >= CLIENT_LIMIT
                || repository.failuresSince(now.minus(DAY)) >= DAILY_LIMIT) {
            throw new TooManyRequestsException("시도가 너무 많습니다. 잠시 뒤 다시 시도해 주세요");
        }
        Settings found = settings.orElseThrow();
        // 아이디가 틀려도 해시 비교를 해서 걸리는 시간으로 아이디를 알아내지 못하게 한다
        boolean passwordMatches =
                request.password().length() <= MAX_PASSWORD_LENGTH && matches(request.password(), found.passwordHash());
        if (!found.loginId().equals(request.loginId()) || !passwordMatches) {
            repository.recordFailure(client, now, now.minus(DAY.multipliedBy(2)));
            log.warn("심사용 로그인 실패: client={}", client);
            throw new BadRequestException(ErrorCode.INVALID_REVIEW_CREDENTIALS, "아이디 또는 비밀번호가 맞지 않습니다");
        }
        return accounts.signIn(found);
    }

    private boolean matches(String password, String hash) {
        try {
            return encoder.matches(password, hash);
        } catch (IllegalArgumentException e) {
            // 해시가 BCrypt 형식이 아니다. 같은 시간을 쓰고 틀린 것으로 본다
            encoder.matches(password, DUMMY_HASH);
            return false;
        }
    }

    private static boolean enabled(Optional<Settings> settings, String appVersion) {
        return settings.filter(found -> found.releasedVersion() != null)
                .filter(found -> AppVersions.isNewer(appVersion, found.releasedVersion()))
                .isPresent();
    }
}
