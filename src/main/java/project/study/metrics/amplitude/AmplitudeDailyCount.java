package project.study.metrics.amplitude;

import java.time.LocalDate;

/** 유저 × KST 날짜 × 이벤트 키의 횟수 (V28 amplitude_daily_event 한 행). */
public record AmplitudeDailyCount(long userId, LocalDate date, String key, int count) {}
