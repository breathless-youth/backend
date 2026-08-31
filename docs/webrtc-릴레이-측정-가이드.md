# WebRTC 릴레이 비율 측정 가이드 (프론트 전달용)

> **목적**: WebRTC 연결이 실제로 **TURN 릴레이(coturn 경유)**를 타는 비율과 그로 인한 **egress(네트워크 비용)**를 실측하기 위한 프론트↔백엔드 스펙.
> **왜 필요한가**: coturn egress 비용은 "릴레이 비율 × bitrate × 시간"으로 결정되는데, 지금은 릴레이 비율을 아무도 모른다. 이걸 측정해야 STUN/IPv6/bitrate 최적화의 효과를 판단할 수 있다.

---

## 1. 개념 — "선택된 candidate pair의 타입"을 본다

WebRTC 연결이 맺어지면 실제 사용 경로가 `candidate-pair` 하나로 확정된다. 그 pair의 **local candidate 타입**이 이 연결의 정체다:

| candidateType | 의미 | coturn 비용 |
|---|---|---|
| `host` | 같은 망 직결 | ❌ 없음 |
| `srflx` | STUN으로 뚫은 P2P | ❌ 없음 |
| `prflx` | peer-reflexive (P2P 일종) | ❌ 없음 |
| **`relay`** | **TURN 릴레이 (coturn 경유)** | 💰 **있음** |

→ **local candidateType === 'relay'** 인 연결이 coturn egress를 유발한다. 이걸 세면 릴레이 비율이 나온다.
→ 릴레이 연결의 **`bytesReceived`** = coturn이 이 클라에게 내보낸 양 ≈ **실제 egress**.

---

## 2. 프론트 구현

### 2-1. 수집 함수 (연결에서 타입 뽑기)

```js
async function getConnectionType(pc) {
  const stats = await pc.getStats();

  // 선택된 pair 찾기: transport.selectedCandidatePairId (최신) → fallback nominated+succeeded
  let selectedPairId = null;
  stats.forEach((r) => {
    if (r.type === 'transport' && r.selectedCandidatePairId) selectedPairId = r.selectedCandidatePairId;
  });
  let pair = selectedPairId ? stats.get(selectedPairId) : null;
  if (!pair) {
    stats.forEach((r) => {
      if (r.type === 'candidate-pair' && r.state === 'succeeded' && r.nominated) pair = r;
    });
  }
  if (!pair) return null;

  const local = stats.get(pair.localCandidateId);
  return {
    candidateType: local?.candidateType, // host | srflx | prflx | relay
    relayProtocol: local?.relayProtocol, // relay일 때 udp | tcp | tls
    bytesReceived: pair.bytesReceived, // ★ 릴레이면 곧 coturn egress량
    bytesSent: pair.bytesSent,
    rttMs: pair.currentRoundTripTime ? Math.round(pair.currentRoundTripTime * 1000) : null,
    usingRelay: local?.candidateType === 'relay',
  };
}
```

### 2-2. 리포팅 (언제·뭘 보내나)

선택 경로는 **중간에 바뀔 수 있다**(continual ICE로 relay→P2P 전환). 그래서 **연결 시 1회 + 주기(60초) + 종료 시 1회** 샘플링한다:

```js
function attachRtcStats(pc, { roomId, userId, peerUserId }) {
  let timer = null;
  const connectionId = crypto.randomUUID(); // ★ PeerConnection당 1개 — 연결 단위 dedup 키
  const sample = async (isFinal = false) => {
    const t = await getConnectionType(pc);
    if (!t) return;
    const payload = JSON.stringify({
      connectionId, // ← 연결 식별자 (필수)
      roomId,
      userId,
      peerUserId,
      candidateType: t.candidateType, // ← 집계 핵심
      relayProtocol: t.relayProtocol,
      bytesReceived: t.bytesReceived, // ← 누적값 (egress 추정 기준)
      bytesSent: t.bytesSent,
      rttMs: t.rttMs,
      isFinal,
      at: Date.now(),
    });
    // ★ sendBeacon: 문자열을 그냥 넣으면 text/plain으로 가서 서버가 415로 거절한다.
    //    반드시 application/json Blob으로 감싼다. (페이지 이탈 때도 유실 안 됨)
    navigator.sendBeacon('/api/rtc-stats', new Blob([payload], { type: 'application/json' }));
  };
  pc.addEventListener('connectionstatechange', () => {
    if (pc.connectionState === 'connected') {
      sample();
      timer = setInterval(sample, 60000);
    }
    if (['failed', 'disconnected', 'closed'].includes(pc.connectionState)) {
      clearInterval(timer);
      sample(true);
    }
  });
}
```

### 2-3. 주의사항 (프론트)

1. **메시(3인 방)는 유저당 PeerConnection이 2개** → **각 pc마다** `attachRtcStats` 호출. 릴레이 비율은 "유저 단위"가 아니라 **"연결(peer-connection) 단위"**로 잰다.
2. **선택 경로는 변할 수 있다** → 1회가 아니라 주기 샘플링(60s) + 종료 시 최종.
3. **`sendBeacon` 사용** — 통화 끊고 화면 나갈 때 마지막 샘플 유실 방지.
4. `prflx`는 P2P로 취급(릴레이 아님). **`relay`만** 비용.
5. **`bytesReceived`(릴레이 pair) = coturn egress 추정치** — 비율보다 이게 실제 비용에 더 직결.
6. 개인정보 없음 — 후보 타입·바이트·RTT만.

---

## 3. 백엔드 (수집 엔드포인트)

프론트가 보낼 곳만 있으면 된다.

```
POST /api/rtc-stats   (body = 2-2의 JSON)
→ rtc_connection_stat 테이블에 insert (또는 로그 → CloudWatch)
```

**테이블 컬럼** (`rtc_connection_stat`, 마이그레이션 V14):

| 컬럼 | 타입 | 비고 |
|---|---|---|
| connection_id | varchar | **PeerConnection당 UUID — 연결 단위 dedup 키 (필수)** |
| room_id | bigint | |
| user_id | bigint | |
| peer_user_id | bigint | 상대 (nullable) |
| candidate_type | varchar | host/srflx/prflx/relay |
| relay_protocol | varchar | udp/tcp/tls (nullable) |
| bytes_received | bigint | candidate pair 누적값 (egress 추정 기준) |
| bytes_sent | bigint | |
| rtt_ms | int | nullable |
| is_final | boolean | 종료 샘플 여부 |
| client_at | timestamptz | 클라 시계 (nullable) |
| created_at | timestamptz | 서버 수신 시각 |

> **`sendBeacon`은 반드시 `application/json` Blob으로 감쌀 것** (2-2 코드 참고) — 문자열을 그냥 넣으면 `text/plain`으로 전송돼 서버가 **415로 거절**한다.
> 인증은 현재 비활성(전 엔드포인트 permitAll)이라 userId를 body로 받는다. 남용·rate limit 대응은 후속(별도 티켓).

---

## 4. 집계 쿼리

> **중요 — connection_id로 dedup 필수.** 한 연결이 `disconnected→connected→closed`를 거치면 final 행이 여러 개 생기고, 재전송 중복도 있다. 그래서 집계는 **connection_id별 "가장 최근 final 1건"만** 골라야 한다 (아래 `latest` CTE).

```sql
-- connection별 최종(가장 최근 final) 샘플 1건만 추림
WITH latest AS (
  SELECT DISTINCT ON (connection_id) *
  FROM rtc_connection_stat
  WHERE is_final
  ORDER BY connection_id, created_at DESC
)
-- 릴레이 비율 (연결 단위)
SELECT
  count(*) FILTER (WHERE candidate_type = 'relay')::float / NULLIF(count(*), 0) AS relay_ratio,
  count(*) AS total_connections
FROM latest;

-- coturn egress 추정 (최종이 relay인 연결의 누적 bytes_received 합)
WITH latest AS (
  SELECT DISTINCT ON (connection_id) *
  FROM rtc_connection_stat
  WHERE is_final
  ORDER BY connection_id, created_at DESC
)
SELECT sum(bytes_received) / 1e9 AS coturn_egress_gb
FROM latest
WHERE candidate_type = 'relay';
```

> **egress 추정의 한계**: `bytes_received`는 candidate pair **누적값**이라, connection별 최종 1건만 합해야 한다(주기 샘플을 다 더하면 중복 계산). 또한 연결이 도중에 `relay→host`로 갈아타면 이미 쓴 TURN egress가 누락되므로 이 추정은 **하한**이다. 더 정밀하게 하려면 프론트가 구간별 delta를 보내거나 `(connection_id, candidate_pair)`별 시계열 delta를 계산해야 한다 (후속 개선).

---

## 5. 이 측정으로 판단할 것

- **릴레이 비율** 실측 → STUN 추가·IPv6 도입 전후 비교로 효과 검증.
- **coturn egress(GB)** 실측 → AWS Data Transfer 청구서와 대조, launch 후 비용 예측.
- 릴레이 비율이 높으면(특히 모바일) → IPv6 도입 우선순위↑, bitrate 캡 검토.

---

## 부록 — 왜 이게 정확한가

- coturn 서버 로그로도 릴레이량을 알 수 있지만, **프론트 `getStats()`는 "P2P였는지 relay였는지"를 연결별로 정확히 구분**해준다(서버 로그는 allocation은 보이나 실제 미디어가 P2P로 빠졌는지 판정이 어려움).
- `bytesReceived`(relay pair) ≈ coturn→클라 방향 전송량 = **egress 방향과 일치**. 그래서 비용 추정에 바로 쓸 수 있다.
