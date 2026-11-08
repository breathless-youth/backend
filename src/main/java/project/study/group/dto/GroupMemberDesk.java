package project.study.group.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import project.study.group.DeskStatus;

/**
 * 멤버 책상 — 내 그룹 카드, 그룹 홈, 공개 그룹 보기가 함께 쓴다. 순공은 앱 전체 기준이다: 측정 세션만, 1초 미만 조각 제외, 진행 중인 세션 포함
 * (랭킹과 같다).
 */
public record GroupMemberDesk(
        @Schema(description = "멤버 userId", example = "42") long userId,
        @Schema(description = "닉네임", example = "새벽형인간") String nickname,
        @Schema(description = "프로필 이미지 URL — 없으면 null") String profileImageUrl,

        @Schema(description = "그룹장이면 true", example = "false")
        boolean owner,

        @Schema(
                description = "지금 상태 — `IN_ROOM` 이 그룹 공부방에 있음(카메라를 껐거나 휴식 중이어도 포함), "
                        + "`STUDYING` 다른 곳에서 측정 중, `PAUSED` 다른 곳에서 일시정지, `IDLE` 공부 안 함",
                example = "STUDYING")
        DeskStatus status,

        @Schema(description = "지금 순공이 쌓이는 중이면 true — 응답을 받은 뒤로 FE가 1초씩 올린다", example = "true")
        boolean accumulating,

        @Schema(description = "오늘 순공(초) — 앱 전체, 오늘은 KST 달력 날짜 기준, 응답 시각까지. 기록 탭 일간과 같은 날짜 경계다", example = "5400")
        long todayFocusSec,

        @Schema(description = "이번 주 순공(초) — 앱 전체, 월요일 0시 KST부터 응답 시각까지. 그룹 안 순위는 이 값으로 FE가 정렬한다", example = "36000")
        long weekFocusSec,

        @Schema(description = "앱에서 마지막으로 공부한 때(UTC ISO-8601) — 공부 기록이 없으면 null", example = "2026-11-03T05:30:00Z")
        Instant lastStudiedAt) {}
