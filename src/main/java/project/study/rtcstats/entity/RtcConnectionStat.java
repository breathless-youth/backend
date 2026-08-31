package project.study.rtcstats.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * WebRTC 연결 통계 1건 (BY-490). 프론트 getStats() 샘플을 그대로 적재한다.
 *
 * <p>candidateType이 {@code relay}인 행이 coturn egress를 유발한 연결이고, 그 행의 bytesReceived 합이 실제 egress 추정치다.
 */
@Entity
@Getter
@Builder
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RtcConnectionStat {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String connectionId;
    private Long roomId;
    private Long userId;
    private Long peerUserId;
    private String candidateType;
    private String relayProtocol;
    private Long bytesReceived;
    private Long bytesSent;
    private Integer rttMs;
    private boolean isFinal;
    private Instant clientAt;
    private Instant createdAt;
}
