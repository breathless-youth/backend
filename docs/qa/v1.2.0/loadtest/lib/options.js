// 공통 램프(계단식) 옵션 (BY-462)
// 목표 동시유저(peak)에 비례해 단계를 스케일한다. 각 단계를 3~5분씩 유지하며
// p99가 꺾이거나 에러가 시작되는 "무릎점(knee)"을 찾는다.
export function stagesFor(peak) {
  const at = (f) => Math.max(1, Math.round(peak * f));
  return [
    { duration: '2m', target: at(0.1) },
    { duration: '3m', target: at(0.1) },
    { duration: '2m', target: at(0.3) },
    { duration: '3m', target: at(0.3) },
    { duration: '2m', target: at(0.5) },
    { duration: '3m', target: at(0.5) },
    { duration: '2m', target: at(0.75) },
    { duration: '3m', target: at(0.75) },
    { duration: '3m', target: peak },
    { duration: '5m', target: peak }, // 목표 부하 유지(soak)
    { duration: '2m', target: 0 },
  ];
}

/** 서버가 보낸 STOMP 본문(JSON)에서 type 필드를 안전하게 읽는다 */
export function messageType(body) {
  try {
    return JSON.parse(body).type;
  } catch (_) {
    return undefined;
  }
}
