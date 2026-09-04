// 부하테스트 공통 REST 헬퍼 (BY-462)
// setup() 단계에서 유저/방을 미리 만들 때 쓴다. 인증이 비활성(permitAll)이라
// 토큰 없이 userId 만으로 호출한다.
import http from 'k6/http';
import { uuidv4 } from './stomp.js';

const JSON_HEADERS = { headers: { 'Content-Type': 'application/json' } };

/**
 * 유저 N명 등록 → userId 배열 반환.
 * POST /api/users {deviceId} 는 기기당 멱등이라, 랜덤 UUID로 매번 새 유저를 만든다.
 * http.batch 로 청크 단위 동시 요청해 setup 시간을 줄인다.
 */
export function registerUsers(base, n, chunk = 50) {
  const ids = [];
  for (let i = 0; i < n; i += chunk) {
    const reqs = [];
    for (let j = i; j < Math.min(i + chunk, n); j++) {
      reqs.push([
        'POST',
        `${base}/api/users`,
        JSON.stringify({ deviceId: uuidv4() }),
        JSON_HEADERS,
      ]);
    }
    const res = http.batch(reqs);
    for (const r of res) {
      if (r.status === 200 || r.status === 201) ids.push(r.json('userId'));
    }
  }
  if (ids.length < n) {
    console.warn(`registerUsers: 요청 ${n}명 중 ${ids.length}명만 생성됨`);
  }
  return ids;
}

/**
 * creatorIds 각각으로 방 1개씩 생성 → [{roomId, inviteCode}] 반환.
 * 생성만으로는 입장 상태가 아니므로(생성 != join) creator 도 이후 join VU 로 재사용 가능.
 */
export function createRooms(base, creatorIds, chunk = 50) {
  const rooms = [];
  for (let i = 0; i < creatorIds.length; i += chunk) {
    const slice = creatorIds.slice(i, i + chunk);
    const reqs = slice.map((uid) => [
      'POST',
      `${base}/api/rooms`,
      JSON.stringify({ userId: uid }),
      JSON_HEADERS,
    ]);
    const res = http.batch(reqs);
    for (const r of res) {
      if (r.status === 201 || r.status === 200) {
        rooms.push({ roomId: r.json('roomId'), inviteCode: r.json('inviteCode') });
      }
    }
  }
  if (rooms.length < creatorIds.length) {
    console.warn(
      `createRooms: 요청 ${creatorIds.length}개 중 ${rooms.length}개만 생성됨`,
    );
  }
  return rooms;
}
