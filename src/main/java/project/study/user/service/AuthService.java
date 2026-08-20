package project.study.user.service;

// AUTH-DISABLED: 소셜 로그인은 후순위로 미뤄짐 (ADR-0004) — 재도입 시 feature/BY-383-auth-contract 브랜치 참고
//
// import java.nio.charset.StandardCharsets;
// import java.security.MessageDigest;
// import java.security.NoSuchAlgorithmException;
// import java.time.Instant;
// import java.util.HexFormat;
// import java.util.List;
// import java.util.Optional;
// import java.util.UUID;
// import org.springframework.beans.factory.annotation.Value;
// import org.springframework.dao.DataIntegrityViolationException;
// import org.springframework.stereotype.Service;
// import org.springframework.transaction.PlatformTransactionManager;
// import org.springframework.transaction.annotation.Transactional;
// import org.springframework.transaction.support.TransactionTemplate;
// import project.study.common.ConflictException;
// import project.study.common.NotFoundException;
// import project.study.user.dto.LinkSocialRequest;
// import project.study.user.dto.LoginRequest;
// import project.study.user.dto.LoginResponse;
// import project.study.user.dto.RefreshRequest;
// import project.study.user.dto.TokenResponse;
// import project.study.user.entity.Provider;
// import project.study.user.entity.RefreshToken;
// import project.study.user.entity.User;
// import project.study.user.entity.UserStatus;
// import project.study.user.jwt.JwtUtil;
// import project.study.user.oauth.InvalidOAuthTokenException;
// import project.study.user.oauth.OAuthTokenVerifier;
// import project.study.user.oauth.OAuthUserInfo;
// import project.study.user.repository.RefreshTokenRepository;
// import project.study.user.repository.UserRepository;
//
// @Service
// public class AuthService {
//
//     private final List<OAuthTokenVerifier> tokenVerifiers;
//     private final UserRepository userRepository;
//     private final RefreshTokenRepository refreshTokenRepository;
//     private final JwtUtil jwtUtil;
//     private final TransactionTemplate transactionTemplate;
//     private final long refreshExpirationMs;
//
//     public AuthService(
//             List<OAuthTokenVerifier> tokenVerifiers,
//             UserRepository userRepository,
//             RefreshTokenRepository refreshTokenRepository,
//             JwtUtil jwtUtil,
//             PlatformTransactionManager transactionManager,
//             @Value("${refresh-token.expiration}") long refreshExpirationMs) {
//         this.tokenVerifiers = tokenVerifiers;
//         this.userRepository = userRepository;
//         this.refreshTokenRepository = refreshTokenRepository;
//         this.jwtUtil = jwtUtil;
//         this.transactionTemplate = new TransactionTemplate(transactionManager);
//         this.refreshExpirationMs = refreshExpirationMs;
//     }
//
//     public LoginResponse login(LoginRequest request) { ... }
//     public TokenResponse refresh(RefreshRequest request) { ... }
//     public LoginResponse linkSocialAccount(Long userId, LinkSocialRequest request) { ... }
//     public void logout(String refreshToken) { ... }
//     TokenPair issueTokens(Long userId) { ... }
//
//     record TokenPair(String accessToken, String refreshToken) {}
// }
