package project.study.studysession.dto;

import java.time.Instant;
import java.util.List;
import project.study.studysession.entity.EventStatus;

/**
 * 진행 중 세션(draft) 하나의 지금 상태와 조각 (BY-1124 멤버 책상). 조각은 {@link LivePiece}와 같은 분할(자정·시간대, 집중 중이면 asOf까지
 * 연장)이고, 상태는 같은 draft를 한 번 읽어 함께 낸다 — 사람마다 상태와 순공을 따로 읽지 않게 하려는 것이다.
 *
 * @param lastSeenAt 서버가 마지막으로 받은 시각
 * @param openEvent 지금 진행 중인 이벤트(앱이 reportedAt에서 닫아 보낸 마지막 이벤트) — 없으면 null
 * @param focusing 랭킹의 집중 중과 같은 판단 — 마지막 수신이 60초 안이고 진행 중인 이벤트가 없다
 */
public record LiveDraft(
        long draftId,
        long userId,
        Instant lastSeenAt,
        EventStatus openEvent,
        boolean focusing,
        List<LivePiece> pieces) {}
