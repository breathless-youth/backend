// 시나리오 C — 결합(현실) 부하 ⭐핵심 (BY-462)
//
// 실제 운영 상황: "공부 중" 유저 1명 = 웹소켓(룸) 유지 + 30초마다 체크포인트.
// 이 시나리오의 무릎점이 곧 "단일 태스크가 몇 명까지 버티냐"의 답이다.
// (오토스케일이 없으므로 이 수치 = 운영 한계선)
//
// 실행:  k6 run -e BASE_URL=http://<host>:8080 -e WS_URL=ws://<host>:8080/ws \
//               -e PEAK=1000 -e HOLD=300 loadtest/s3-combined.js
import http from 'k6/http';
import { check, sleep } from 'k6';
import { Trend, Rate, Counter } from 'k6/metrics';
import { stompConnect } from './lib/stomp.js';
import { registerUsers, createRooms } from './lib/api.js';
import { stagesFor, messageType } from './lib/options.js';

const BASE = __ENV.BASE_URL || 'http://localhost:8080';
const WS = __ENV.WS_URL || 'ws://localhost:8080/ws';
const PEAK = Number(__ENV.PEAK || 1000);
const HOLD = Number(__ENV.HOLD || 300); // 한 "공부 세션" 길이(초)
const CHECKPOINT_MS = Number(__ENV.CHECKPOINT_MS || 30000); // 체크포인트 주기(ms)
const PER_ROOM = Number(__ENV.PER_ROOM || 6);
const ROOMS = Math.ceil(PEAK / PER_ROOM);
const SIGNAL = !!__ENV.SIGNAL; // WebRTC 시그널 mesh 재현 (6명 런에서만 켠다)

const checkpointDur = new Trend('checkpoint_duration', true);
const subToSnapshot = new Trend('room_subscribe_to_snapshot', true);
const wsConnectOk = new Rate('ws_connect_success');
const joinFail = new Counter('room_join_fail');
const signalSent = new Counter('signal_sent');

// 방 TTL(빈 방 10분) 회피: 1000까지 ~8분에 도달(방들이 만료 전 채워짐) + HOLD 길게(안 나감).
// 각 인원에서 유지하며 지표를 캡처할 수 있게 플래토를 둔다.
const plateauStages = __ENV.SAFECAP
  ? [
      // p99 측정용 안전 구간 (OOM 전, ~500명 상한)
      { duration: '15s', target: 300 }, { duration: '90s', target: 300 },
      { duration: '15s', target: 500 }, { duration: '120s', target: 500 },
      { duration: '15s', target: 0 },
    ]
  : __ENV.TARGET2000
    ? [
        // 2000명 목표 (BY-494 3차): 500/1000/1500/2000, 2000에서 5분 유지
        { duration: '15s', target: 500 }, { duration: '45s', target: 500 },
        { duration: '15s', target: 1000 }, { duration: '45s', target: 1000 },
        { duration: '20s', target: 1500 }, { duration: '45s', target: 1500 },
        { duration: '25s', target: 2000 }, { duration: '180s', target: 2000 },
        { duration: '20s', target: 0 },
      ]
  : __ENV.STEP
    ? (() => {
        // 1000명 단위 계단 (스펙 B 무릎점 탐색): 1000→2000→…→STEP(최대). 각 단계 75s 유지, 최종 단계 300s
        const max = Number(__ENV.STEP);
        const st = [];
        for (let t = 1000; t <= max; t += 1000) {
          st.push({ duration: '25s', target: t }, { duration: t === max ? '180s' : '45s', target: t });
        }
        st.push({ duration: '20s', target: 0 });
        return st;
      })()
  : __ENV.EXTEND
    ? (() => {
        // 런 B 연장: 2000까지 빠르게 올린 뒤 +500씩 계속 (무릎점 탐색, EXTEND=최대 인원)
        const max = Number(__ENV.EXTEND);
        const st = [{ duration: '40s', target: 2000 }, { duration: '60s', target: 2000 }];
        for (let t = 2500; t <= max; t += 500) {
          st.push({ duration: '25s', target: t }, { duration: '75s', target: t });
        }
        st.push({ duration: '20s', target: 0 });
        return st;
      })()
  : __ENV.TARGET1000
    ? [
        // 1000명 목표: 300/500/750/1000, 1000에서 5분 유지 (BY-494)
        { duration: '15s', target: 300 }, { duration: '75s', target: 300 },
        { duration: '15s', target: 500 }, { duration: '75s', target: 500 },
        { duration: '20s', target: 750 }, { duration: '75s', target: 750 },
        { duration: '25s', target: 1000 }, { duration: '300s', target: 1000 },
        { duration: '20s', target: 0 },
      ]
    : [
        { duration: '15s', target: 300 }, { duration: '75s', target: 300 },
        { duration: '15s', target: 500 }, { duration: '75s', target: 500 },
        { duration: '25s', target: 1000 }, { duration: '80s', target: 1000 },
        { duration: '25s', target: 1500 }, { duration: '80s', target: 1500 },
        { duration: '35s', target: 2000 }, { duration: '100s', target: 2000 },
        { duration: '20s', target: 0 },
      ];

// WARMUP=1: 본 램프 앞에 저부하 60초(100명)를 붙여 교체 직후 JIT 콜드 스타트를 측정에서 뺀다 (3차 방법론 개선).
// 워밍업 구간의 지표는 리포트에서 제외하고, 램프/유지 구간을 분리 표기한다.
const warmupStages = __ENV.WARMUP ? [{ duration: '15s', target: 100 }, { duration: '60s', target: 100 }] : [];

export const options = {
  setupTimeout: '600s',
  summaryTrendStats: ['avg', 'min', 'med', 'max', 'p(90)', 'p(95)', 'p(99)'],
  scenarios: {
    combined: {
      executor: 'ramping-vus',
      stages: [...warmupStages, ...(__ENV.PLATEAU ? plateauStages : stagesFor(PEAK))],
      gracefulRampDown: '10s',
    },
  },
  thresholds: {
    checkpoint_duration: ['p(99)<500'],
    room_subscribe_to_snapshot: ['p(99)<1000'],
    ws_connect_success: ['rate>0.99'],
    http_req_failed: ['rate<0.01'],
  },
};

export function setup() {
  const users = registerUsers(BASE, PEAK);
  const rooms = createRooms(BASE, users.slice(0, ROOMS));
  return { users, rooms, startedAt: new Date().toISOString() };
}

export default function (data) {
  const { users, rooms, startedAt } = data;
  if (!rooms.length) return;
  const userId = users[(__VU - 1) % users.length];
  const room = rooms[Math.floor((__VU - 1) / PER_ROOM) % rooms.length];

  // 1) 룸 입장(REST)
  const jr = http.post(
    `${BASE}/api/rooms/join`,
    JSON.stringify({ userId, inviteCode: room.inviteCode }),
    { headers: { 'Content-Type': 'application/json' }, tags: { name: 'join' } },
  );
  if (jr.status !== 200) {
    joinFail.add(1);
    sleep(1);
    return;
  }
  const roomId = jr.json('roomId');

  let subAt = 0;
  let snapMeasured = false;
  const peers = new Set(); // 같은 방 다른 참가자 userId (시그널 대상)
  stompConnect(WS, userId, {
    onReady: (api, socket) => {
      wsConnectOk.add(true);
      // SNAPSHOT 은 개인 큐(/user/queue/room)로 온다 → 먼저 구독.
      api.subscribe(`/user/queue/room`, 0);
      subAt = Date.now();
      api.subscribe(`/topic/room/${roomId}`, 1);
      socket.setTimeout(() => api.sendJson(`/app/room/${roomId}/snapshot`, {}), 500);

      // 10초마다 room state 브로드캐스트
      socket.setInterval(() => {
        api.sendJson(`/app/room/${roomId}/state`, {
          cameraOn: true,
          focusState: 'FOCUSED',
          studySeconds: Math.floor((Date.now() - subAt) / 1000),
        });
      }, 10000);

      // (SIGNAL=1) 현실 모델 (BY-494): 시그널은 상시가 아니라 유지시간 동안 총 SIGNAL_TOTAL회만.
      // 실제 WebRTC 시그널링은 연결 수립 시 버스트 후 침묵하므로, 세션의 10/45/80% 지점에
      // 1건씩(피어 로테이션) 보내 재협상 수준의 저빈도 트래픽을 재현한다.
      if (SIGNAL) {
        const SIGNAL_TOTAL = Number(__ENV.SIGNAL_TOTAL || 3);
        // 스프레드 창(기본 300s) 안에서 10%~80% 지점에 분산 — 늦게 합류한 VU도 세션 내 전부 발송
        const SIGNAL_SPREAD_MS = Number(__ENV.SIGNAL_SPREAD || 300) * 1000;
        for (let k = 0; k < SIGNAL_TOTAL; k++) {
          socket.setTimeout(() => {
            const arr = [...peers];
            if (!arr.length) return; // 스냅샷 전이면 스킵
            const p = arr[k % arr.length];
            api.sendJson(`/app/room/${roomId}/signal`, {
              toUserId: p,
              kind: 'CANDIDATE',
              payload: { c: k },
            });
            signalSent.add(1);
          }, SIGNAL_SPREAD_MS * (0.10 + (0.70 * k) / Math.max(1, SIGNAL_TOTAL - 1)));
        }
      }

      // 30초마다 체크포인트(HTTP) — 같은 유저의 진행중 세션 스냅샷
      socket.setInterval(() => {
        const elapsed = Math.max(
          0,
          Math.floor((Date.now() - Date.parse(startedAt)) / 1000),
        );
        const t0 = Date.now();
        const r = http.put(
          `${BASE}/api/study-sessions/active`,
          JSON.stringify({
            userId,
            startedAt,
            reportedAt: new Date().toISOString(),
            studySec: elapsed,
            focusSec: Math.floor(elapsed * 0.9),
            events: [],
          }),
          {
            headers: { 'Content-Type': 'application/json' },
            tags: { name: 'checkpoint' },
          },
        );
        checkpointDur.add(Date.now() - t0);
        check(r, { 'checkpoint 204': (x) => x.status === 204 });
      }, CHECKPOINT_MS);

      // HOLD 후 세션 종료(커넥션 닫음) → 다음 iteration에서 재접속(이탈/재접속 모델)
      socket.setTimeout(() => socket.close(), HOLD * 1000);
    },
    onMessage: (body) => {
      let msg;
      try {
        msg = JSON.parse(body);
      } catch (_) {
        return;
      }
      const t = msg.type;
      if (t === 'SNAPSHOT') {
        if (!snapMeasured && subAt > 0) {
          subToSnapshot.add(Date.now() - subAt);
          snapMeasured = true;
        }
        // 방 참가자 목록에서 나 아닌 유저를 시그널 피어로 등록
        if (SIGNAL && Array.isArray(msg.members)) {
          for (const m of msg.members) {
            if (m.userId && m.userId !== userId) peers.add(m.userId);
          }
        }
      } else if (t === 'MEMBER_JOINED') {
        if (SIGNAL && msg.member && msg.member.userId !== userId) {
          peers.add(msg.member.userId);
        }
      } else if (t === 'MEMBER_LEFT') {
        if (msg.userId) peers.delete(msg.userId);
      }
    },
    onError: () => wsConnectOk.add(false),
  });
}
