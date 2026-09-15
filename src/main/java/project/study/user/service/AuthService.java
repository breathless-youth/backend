package project.study.user.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import project.study.user.dto.RefreshRequest;
import project.study.user.dto.TokenResponse;
import project.study.user.entity.RefreshToken;
import project.study.user.jwt.JwtUtil;
import project.study.user.repository.RefreshTokenRepository;
import project.study.user.repository.UserRepository;

/**
 * 비회원(DEVICE) 토큰 발급과 refresh 회전. 소셜 로그인(link·logout)은 파킹 상태 — BY-383 브랜치 참고.
 *
 * <p>refresh는 opaque UUID를 SHA-256 해시로만 저장하고, 회전 시 삭제 대신 {@code usedAt}을 마킹한다.
 * 사용된 토큰이 다시 오면 탈취 의심으로 그 유저의 refresh를 전량 폐기한다 (ADR-0002).
 */
@Service
public class AuthService {

    private final UserRepository userRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final JwtUtil jwtUtil;
    private final long refreshExpirationMs;

    public AuthService(
            UserRepository userRepository,
            RefreshTokenRepository refreshTokenRepository,
            JwtUtil jwtUtil,
            @Value("${refresh-token.expiration}") long refreshExpirationMs) {
        this.userRepository = userRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.jwtUtil = jwtUtil;
        this.refreshExpirationMs = refreshExpirationMs;
    }

    /**
     * 기기 등록(재등록)용 발급 — 이 유저의 refresh를 전량 폐기한 뒤 새 쌍을 만든다.
     *
     * <p>DEVICE 유저는 기기 하나라는 전제라, 재등록은 곧 "다른 곳에 남은 refresh를 무효화"하는 동작이다.
     * 재사용 감지용 tombstone까지 지워지지만, 남는 토큰이 하나도 없으므로 옛 토큰이 다시 와도
     * "알 수 없는 토큰"으로 거부되어 문제없다. 회전({@link #refresh})은 tombstone을 남겨야 하므로 이 경로를 쓰지 않는다.
     */
    @Transactional
    public TokenPair issueTokensRevokingExisting(Long userId) {
        refreshTokenRepository.deleteByUserId(userId);
        return issueTokens(userId);
    }

    // noRollbackFor: 재사용 감지 시 예외를 던져도 "전체 폐기"는 커밋되어야 한다
    @Transactional(noRollbackFor = InvalidRefreshTokenException.class)
    public TokenResponse refresh(RefreshRequest request) {
        RefreshToken saved = refreshTokenRepository
                .findByTokenHash(sha256(request.refreshToken()))
                .orElseThrow(() -> new InvalidRefreshTokenException("유효하지 않은 refresh 토큰입니다"));

        // 재사용 검사가 만료 검사보다 먼저다: 만료를 먼저 보면 탈취자가 회전시킨 토큰이 만료된 뒤
        // 피해자가 재시도할 때 행만 삭제되고 끝나 전량 폐기가 안 일어난다(재사용 감지 우회)
        if (saved.getUsedAt() != null) {
            refreshTokenRepository.deleteByUserId(saved.getUserId());
            throw new InvalidRefreshTokenException("이미 사용된 refresh 토큰입니다");
        }
        if (saved.isExpired(Instant.now())) {
            refreshTokenRepository.delete(saved);
            throw new InvalidRefreshTokenException("만료된 refresh 토큰입니다");
        }
        // 조건부 UPDATE의 행 잠금이 동시 요청을 직렬화한다 — 정확히 한쪽만 사용 처리에 성공
        // (위 usedAt 검사는 조회 시점 스냅샷이라 동시 요청 레이스는 여기서만 걸러진다)
        if (refreshTokenRepository.markUsedIfUnused(saved.getId(), Instant.now()) == 0) {
            // 이미 사용된 토큰의 재등장 = 재사용(탈취 의심) → 해당 유저 토큰 전체 폐기
            refreshTokenRepository.deleteByUserId(saved.getUserId());
            throw new InvalidRefreshTokenException("이미 사용된 refresh 토큰입니다");
        }

        // 유저가 존재하는지만 확인 — refresh 토큰이 살아있어도 유저가 삭제됐을 수 있다
        userRepository
                .findById(saved.getUserId())
                .orElseThrow(() -> new InvalidRefreshTokenException("유효하지 않은 refresh 토큰입니다"));

        TokenPair tokens = issueTokens(saved.getUserId());
        return new TokenResponse(tokens.accessToken(), tokens.refreshToken());
    }

    TokenPair issueTokens(Long userId) {
        String accessToken = jwtUtil.createAccessToken(userId);
        String refreshToken = UUID.randomUUID().toString();
        refreshTokenRepository.save(
                new RefreshToken(userId, sha256(refreshToken), Instant.now().plusMillis(refreshExpirationMs)));
        return new TokenPair(accessToken, refreshToken);
    }

    private static String sha256(String token) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256을 사용할 수 없습니다", e);
        }
    }

    public record TokenPair(String accessToken, String refreshToken) {}
}
