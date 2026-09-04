// 시나리오 A — 체크포인트 HTTP 부하 (BY-462)
//
// 목적: PUT /api/study-sessions/active 가 동시 몇 명까지 견디나.
//       병목 1순위 후보는 HikariCP pool=10 (기본값) → 커넥션 획득 대기.
//
// 실행:  k6 run -e BASE_URL=http://<host>:8080 -e PEAK=1000 loadtest/s1-checkpoint.js
import http from 'k6/http';
import { check, sleep } from 'k6';
import { Trend } from 'k6/metrics';
import { registerUsers } from './lib/api.js';
import { stagesFor } from './lib/options.js';

const BASE = __ENV.BASE_URL || 'http://localhost:8080';
const PEAK = Number(__ENV.PEAK || 1000);

const checkpointDur = new Trend('checkpoint_duration', true);

export const options = {
  setupTimeout: '180s', // 유저 대량 등록 여유
  scenarios: {
    checkpoint: { executor: 'ramping-vus', stages: stagesFor(PEAK) },
  },
  thresholds: {
    checkpoint_duration: ['p(99)<500'],
    http_req_failed: ['rate<0.01'],
  },
};

export function setup() {
  const users = registerUsers(BASE, PEAK);
  // 세션 시작 시각은 전 유저 공통 고정값 — (userId, startedAt) 이 draft 멱등키라
  // userId 가 다르면 행이 겹치지 않고, 같은 VU는 매 30초 같은 행을 UPSERT 한다.
  return { users, startedAt: new Date().toISOString() };
}

export default function (data) {
  const { users, startedAt } = data;
  const userId = users[(__VU - 1) % users.length];

  // studySec 은 세션 경과시간 이하여야 한다(서버 검증). startedAt 이후 실제 경과 초를 쓴다.
  const elapsedSec = Math.max(
    0,
    Math.floor((Date.now() - Date.parse(startedAt)) / 1000),
  );
  const body = JSON.stringify({
    userId,
    startedAt,
    reportedAt: new Date().toISOString(),
    studySec: elapsedSec,
    focusSec: Math.floor(elapsedSec * 0.9),
    events: [],
  });

  const t0 = Date.now();
  const res = http.put(`${BASE}/api/study-sessions/active`, body, {
    headers: { 'Content-Type': 'application/json' },
    tags: { name: 'checkpoint' },
  });
  checkpointDur.add(Date.now() - t0);
  check(res, { 'checkpoint 204': (r) => r.status === 204 });

  sleep(30); // 실제 앱처럼 30초 주기
}
