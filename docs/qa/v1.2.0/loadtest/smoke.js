// 스모크 테스트 — 전체 흐름 1회 검증 (BY-462)
// 라이브 서버에 대고 등록→방생성→join→STOMP연결→구독→SNAPSHOT수신→state전송→체크포인트
// 까지 실제로 되는지 확인한다. 부하가 아니라 "프레이밍/엔드포인트가 맞나" 검증용.
//
// 실행: k6 run loadtest/smoke.js   (기본 localhost:8080)
import http from 'k6/http';
import { check } from 'k6';
import { Counter } from 'k6/metrics';
import { stompConnect } from './lib/stomp.js';
import { registerUsers, createRooms } from './lib/api.js';
import { messageType } from './lib/options.js';

const BASE = __ENV.BASE_URL || 'http://localhost:8080';
const WS = __ENV.WS_URL || 'ws://localhost:8080/ws';

const snapshotReceived = new Counter('smoke_snapshot_received');
const checkpointOk = new Counter('smoke_checkpoint_204');

export const options = {
  vus: 2, // 같은 방에 2명 → MEMBER_JOINED/브로드캐스트도 유발
  iterations: 2,
  setupTimeout: '60s',
};

export function setup() {
  const users = registerUsers(BASE, 2);
  check(null, { '유저 2명 등록됨': () => users.length === 2 });
  const rooms = createRooms(BASE, [users[0]]); // 방 1개에 둘 다 입장
  check(null, { '방 1개 생성됨': () => rooms.length === 1 });
  console.log(`setup: users=${JSON.stringify(users)} rooms=${JSON.stringify(rooms)}`);
  return { users, rooms, startedAt: new Date().toISOString() };
}

export default function (data) {
  const { users, rooms, startedAt } = data;
  const userId = users[(__VU - 1) % users.length];
  const room = rooms[0];

  // 1) 룸 입장(REST)
  const jr = http.post(
    `${BASE}/api/rooms/join`,
    JSON.stringify({ userId, inviteCode: room.inviteCode }),
    { headers: { 'Content-Type': 'application/json' } },
  );
  const joinOk = check(jr, { 'join 200': (r) => r.status === 200 });
  if (!joinOk) {
    console.error(`join 실패: status=${jr.status} body=${jr.body}`);
    return;
  }
  const roomId = jr.json('roomId');
  console.log(`VU${__VU}: joined room ${roomId} as user ${userId}`);

  // 2) STOMP 연결 → 구독 → SNAPSHOT 수신 확인 → state 전송 → 체크포인트
  let gotSnapshot = false;
  stompConnect(WS, userId, {
    onReady: (api, socket) => {
      console.log(`VU${__VU}: STOMP CONNECTED`);
      api.subscribe(`/user/queue/room`, 0); // SNAPSHOT 은 개인 큐로 온다
      api.subscribe(`/topic/room/${roomId}`, 1); // 입장 확정 트리거
      socket.setTimeout(() => api.sendJson(`/app/room/${roomId}/snapshot`, {}), 500); // 재요청(레이스 대비)

      // 잠시 뒤 state 전송 + 체크포인트, 그다음 종료
      socket.setTimeout(() => {
        api.sendJson(`/app/room/${roomId}/state`, {
          cameraOn: true,
          focusState: 'FOCUSED',
          studySeconds: 30,
        });
        const elapsed = Math.max(
          0,
          Math.floor((Date.now() - Date.parse(startedAt)) / 1000),
        );
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
          { headers: { 'Content-Type': 'application/json' } },
        );
        const cpOk = check(r, { 'checkpoint 204': (x) => x.status === 204 });
        if (cpOk) checkpointOk.add(1);
        else console.error(`checkpoint 실패: status=${r.status} body=${r.body}`);
      }, 2000);

      socket.setTimeout(() => socket.close(), 5000);
    },
    onMessage: (body) => {
      const type = messageType(body);
      console.log(`VU${__VU}: 수신 type=${type}`);
      if (type === 'SNAPSHOT' && !gotSnapshot) {
        gotSnapshot = true;
        snapshotReceived.add(1);
      }
    },
    onError: (e) => console.error(`VU${__VU}: STOMP ERROR ${e}`),
    onClose: () => {
      check(null, { 'SNAPSHOT 수신함': () => gotSnapshot });
    },
  });
}
