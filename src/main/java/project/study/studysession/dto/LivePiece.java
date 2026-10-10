package project.study.studysession.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import project.study.studysession.entity.SessionSlot;

/**
 * 진행 중 세션(draft)을 확정과 같은 규칙으로 나눈 조각 하나 (BY-828). latest는 draft의 마지막 조각(지금 시각이 속한 조각),
 * focusing은 draft가 지금 집중 중인지, achievedAt은 그 값의 기준 시각(집중 중이면 asOf, 아니면 마지막 수신)이다.
 * draftId는 조각이 나온 draft(active_study_session)의 id — 하트비트 UPSERT에도 그대로이고 확정·폐기로 draft가 지워지면
 * 사라지므로, 캐시된 조각이 이미 확정된 세션인지 가려내는 데 쓴다.
 */
public record LivePiece(
        long userId,
        LocalDate statDate,
        int focusSec,
        int studySec,
        List<SessionSlot> slots,
        boolean latest,
        boolean focusing,
        Instant achievedAt,
        long draftId) {}
