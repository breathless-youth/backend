package project.study.user.repository;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * link에 쓴 소셜 ID 토큰의 사용 기록 (ADR-0042). 토큰 원문은 받지 않고 SHA-256 hex만 다룬다 — 원문도 해시도 로그에 남기지 않는다.
 *
 * <p>link 트랜잭션 안에서 부른다. 그래야 전환 경쟁으로 롤백된 시도는 토큰을 쓰지 않은 것이 되어 재시도가 같은 토큰을 쓸 수 있고, 같은 토큰이
 * 동시에 두 번 오면 나중 쪽은 먼저 쪽이 끝날 때까지 기본 키에서 기다렸다가 0행을 받는다.
 */
@Repository
@RequiredArgsConstructor
public class UsedSocialIdTokenRepository {

    /**
     * 토큰의 exp 뒤에도 기록을 두는 여유. 검증하는 쪽(구글·카카오 서버)의 시계가 DB보다 늦으면 exp가 조금 지난 토큰도 받히므로, 그 사이에
     * 기록이 지워져 같은 토큰을 다시 쓰지 못하게 한다.
     */
    static final Duration RETENTION_AFTER_EXPIRY = Duration.ofMinutes(5);

    private final JdbcClient jdbc;

    /** 만료된 기록을 치운 뒤 이 토큰을 사용 처리한다(기록은 exp + 5분까지 둔다). 이미 쓴 토큰이면 false. */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean markUsed(String tokenHash, Instant expiresAt) {
        // 다른 link가 지우는 중인 행은 건너뛴다 — 두 정리가 서로 다른 순서로 행을 잡아 교착하지 않게
        jdbc.sql("""
                        DELETE FROM used_social_id_token
                        WHERE token_hash IN (SELECT token_hash FROM used_social_id_token
                                             WHERE expires_at < now() FOR UPDATE SKIP LOCKED)""").update();
        return jdbc.sql("""
                        INSERT INTO used_social_id_token (token_hash, expires_at) VALUES (:hash, :expiresAt)
                        ON CONFLICT DO NOTHING""")
                        .param("hash", tokenHash)
                        .param(
                                "expiresAt",
                                expiresAt.plus(RETENTION_AFTER_EXPIRY).atOffset(ZoneOffset.UTC))
                        .update()
                == 1;
    }
}
