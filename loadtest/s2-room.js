// 시나리오 B — STOMP 웹소켓 룸 부하 (BY-462)
//
// 목적: 실시간 스터디룸이 동시 몇 방/몇 명까지 견디나.
//       병목 후보: RoomService 전역 synchronized 락, 하트비트 스케줄러(poolSize=1),
//                 roomHistoryExecutor 단일 스레드, WebRTC 시그널 릴레이(6인 방 O(n^2)).
//
// 실행:  k6 run -e BASE_URL=http://<host>:8080 -e WS_URL=ws://<host>:8080/ws \
//               -e PEAK=1000 -e HOLD=60 loadtest/s2-room.js
import http from 'k6/http';
import { sleep } from 'k6';
import { Trend, Rate, Counter } from 'k6/metrics';
import { stompConnect } from './lib/stomp.js';
import { registerUsers, createRooms } from './lib/api.js';
import { stagesFor, messageType } from './lib/options.js';

const BASE = __ENV.BASE_URL || 'http://localhost:8080';
const WS = __ENV.WS_URL || 'ws://localhost:8080/ws';
const PEAK = Number(__ENV.PEAK || 1000);
const HOLD = Number(__ENV.HOLD || 60); // WS 커넥션 유지 시간(초)
const ROOM_CAP = 6;
const ROOMS = Math.ceil(PEAK / ROOM_CAP);

const subToSnapshot = new Trend('room_subscribe_to_snapshot', true);
const wsConnectOk = new Rate('ws_connect_success');
const joinFail = new Counter('room_join_fail');

export const options = {
  setupTimeout: '180s',
  scenarios: {
    room: { executor: 'ramping-vus', stages: stagesFor(PEAK) },
  },
  thresholds: {
    room_subscribe_to_snapshot: ['p(99)<1000'],
    ws_connect_success: ['rate>0.99'],
    http_req_failed: ['rate<0.02'],
  },
};

export function setup() {
  const users = registerUsers(BASE, PEAK);
  // 방은 peak/6 개 — VU를 6명씩 같은 방에 몰아넣어 방 내부 부하(락·시그널)를 만든다.
  const rooms = createRooms(BASE, users.slice(0, ROOMS));
  return { users, rooms };
}

export default function (data) {
  const { users, rooms } = data;
  if (!rooms.length) return;
  const userId = users[(__VU - 1) % users.length];
  const room = rooms[(__VU - 1) % rooms.length];

  // 1) 자리 예약(REST) — 30초 TTL, 이후 STOMP 구독으로 확정
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

  // 2) STOMP 연결 → 구독(입장확정) → state/signal 주기 전송 → HOLD 후 종료
  let subAt = 0;
  let snapMeasured = false;
  stompConnect(WS, userId, {
    onReady: (api, socket) => {
      wsConnectOk.add(true);
      // SNAPSHOT 은 개인 큐(/user/queue/room)로 온다 → 먼저 구독해 레이스를 줄인다.
      api.subscribe(`/user/queue/room`, 0);
      subAt = Date.now();
      api.subscribe(`/topic/room/${roomId}`, 1); // 구독 = 입장 확정 → SNAPSHOT 발사
      // 구독 등록 전 발사된 SNAPSHOT 증발 대비 재요청(BY-442)
      socket.setTimeout(() => api.sendJson(`/app/room/${roomId}/snapshot`, {}), 500);

      let sec = 0;
      socket.setInterval(() => {
        sec += 10;
        // 방 전체 브로드캐스트 유발
        api.sendJson(`/app/room/${roomId}/state`, {
          cameraOn: true,
          focusState: 'FOCUSED',
          studySeconds: sec,
        });
        // WebRTC 시그널 릴레이 경로도 태운다 (전역 락 진입)
        api.sendJson(`/app/room/${roomId}/signal`, {
          toUserId: userId,
          kind: 'CANDIDATE',
          payload: { seq: sec },
        });
      }, 10000);

      socket.setTimeout(() => socket.close(), HOLD * 1000);
    },
    onMessage: (body) => {
      if (!snapMeasured && subAt > 0 && messageType(body) === 'SNAPSHOT') {
        subToSnapshot.add(Date.now() - subAt);
        snapMeasured = true;
      }
    },
    onError: () => wsConnectOk.add(false),
  });
}
