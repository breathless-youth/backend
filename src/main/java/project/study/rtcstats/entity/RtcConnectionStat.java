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

    /** 프론트가 부여한 이 P2P 연결의 식별자 — 같은 연결의 중간·최종 샘플을 묶는 키. 재접속하면 새 값이 온다. */
    private String connectionId;

    /** 이 연결이 속한 스터디룸 ID. 룸 단위로 relay 사용량·품질을 집계할 때 쓴다. */
    private Long roomId;

    /** 이 샘플을 보고한 유저(로컬 피어) ID. */
    private Long userId;

    /** 상대 피어의 유저 ID — 어떤 두 사람 사이의 연결인지 식별한다. 그룹룸이라 userId당 여러 peerUserId가 나올 수 있다. */
    private Long peerUserId;

    /**
     * 선택된 ICE 후보 타입: host(같은 LAN)·srflx(STUN, 공인 IP 직결)·prflx(피어 리플렉시브)·relay(TURN 경유).
     * relay인 행만 coturn을 실제로 거친 연결이다 — egress 비용·품질 분석의 1차 필터.
     */
    private String candidateType;

    /** relay일 때 TURN 전송 프로토콜: udp·tcp·tls. relay가 아니면 null. UDP 차단망에서 tcp/tls 폴백 비중을 본다. */
    private String relayProtocol;

    /**
     * 이 연결로 로컬이 수신한 누적 바이트(프론트 getStats 원값). candidateType=relay 행의 합이 coturn egress 추정치다
     * (클래스 주석 참고). 누적값이라 최종 샘플(isFinal) 기준으로 집계한다.
     */
    private Long bytesReceived;

    /** 이 연결로 로컬이 송신한 누적 바이트(프론트 getStats 원값). 누적값. */
    private Long bytesSent;

    /** 왕복 지연(ms) — 샘플 시점의 연결 품질 지표. getStats에 값이 없으면 null. */
    private Integer rttMs;

    /**
     * 연결 종료 시점의 마지막 샘플이면 true. 중간(주기적) 샘플은 false.
     * 누적 바이트·최종 품질을 셀 때 연결당 isFinal 행 하나만 골라 중복 합산을 막는다.
     */
    private boolean isFinal;

    /** 샘플을 찍은 클라이언트 시각(epoch millis → Instant). 프론트가 at을 안 보내면 null. 기기 시계라 신뢰도는 낮다. */
    private Instant clientAt;

    /** 서버가 이 행을 적재한 시각(수신 시각). 신뢰 가능한 서버 시계라 시계열 집계·정렬의 기준으로 쓴다. */
    private Instant createdAt;
}
