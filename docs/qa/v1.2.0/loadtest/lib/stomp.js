// STOMP over k6/ws 헬퍼 (BY-462)
//
// k6에는 STOMP 네이티브 지원이 없어 텍스트 프레임을 직접 조립한다.
// 서버는 STOMP over 순수 WebSocket(SockJS 미사용, 하트비트 10초)이며,
// 핸드셰이크 시 쿼리파라미터 userId 를 Principal 로 신뢰한다(WebSocketConfig).
import ws from 'k6/ws';

const NUL = '\u0000'; // STOMP 프레임 종료 바이트

/** STOMP 프레임 조립: COMMAND\nheader:value\n...\n\nbody\0 */
function frame(command, headers, body = '') {
  const head = Object.entries(headers)
    .map(([k, v]) => `${k}:${v}`)
    .join('\n');
  return `${command}\n${head}\n\n${body}${NUL}`;
}

/** MESSAGE 프레임에서 본문(빈 줄 이후, 끝 NUL 제거)만 추출 */
function parseBody(data) {
  const idx = data.indexOf('\n\n');
  if (idx < 0) return '';
  let body = data.slice(idx + 2);
  if (body.endsWith(NUL)) body = body.slice(0, -1);
  return body;
}

/**
 * userId로 STOMP 연결을 열고, CONNECTED 되면 handlers.onReady(api, socket) 호출.
 *
 * @param {string} wsUrl   ws://host:8080/ws
 * @param {number} userId  존재하는 유저 ID (핸드셰이크에서 Principal 로 쓰임)
 * @param {object} handlers { onReady(api, socket), onMessage(body, raw), onError(raw), onClose() }
 *
 * api: {
 *   subscribe(destination, id)   // SUBSCRIBE — /topic/room/{id} 구독이 입장 확정 트리거
 *   sendJson(destination, obj)   // SEND (content-type: application/json)
 * }
 *
 * ws.connect 는 소켓이 닫힐 때까지 블로킹한다. 연결을 일정 시간 유지하려면
 * onReady 안에서 socket.setTimeout(() => socket.close(), ms) 를 걸어라.
 */
export function stompConnect(wsUrl, userId, handlers = {}) {
  const url = `${wsUrl}?userId=${userId}`;
  return ws.connect(url, {}, (socket) => {
    const api = {
      subscribe: (destination, id = 0) =>
        socket.send(frame('SUBSCRIBE', { id: `sub-${id}`, destination })),
      sendJson: (destination, obj) =>
        socket.send(
          frame(
            'SEND',
            { destination, 'content-type': 'application/json' },
            JSON.stringify(obj),
          ),
        ),
    };

    socket.on('open', () => {
      // accept-version 1.2, 하트비트 10초(서버 설정과 일치)
      socket.send(
        frame('CONNECT', {
          'accept-version': '1.2',
          'heart-beat': '10000,10000',
        }),
      );
    });

    socket.on('message', (data) => {
      const nl = data.indexOf('\n');
      const command = nl < 0 ? data : data.slice(0, nl);
      if (command === 'CONNECTED') {
        // 서버 하트비트를 맞춰줘야 끊기지 않는다 (LF 1바이트)
        socket.setInterval(() => socket.send('\n'), 10000);
        handlers.onReady && handlers.onReady(api, socket);
      } else if (command === 'MESSAGE') {
        handlers.onMessage && handlers.onMessage(parseBody(data), data);
      } else if (command === 'ERROR') {
        handlers.onError && handlers.onError(data);
      }
      // 하트비트(빈 '\n')와 RECEIPT 는 무시
    });

    socket.on('error', (e) => handlers.onError && handlers.onError(String(e)));
    socket.on('close', () => handlers.onClose && handlers.onClose());
  });
}

/** deviceId 등록 등에 쓰는 UUID v4 */
export function uuidv4() {
  return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, (c) => {
    const r = Math.floor(Math.random() * 16);
    const v = c === 'x' ? r : (r & 0x3) | 0x8;
    return v.toString(16);
  });
}
