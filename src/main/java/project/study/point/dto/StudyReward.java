package project.study.point.dto;

import java.time.LocalDate;

/** 한 번의 세션 제출이 받은 공부 포인트와 그날의 인정 순공. */
public record StudyReward(int earnedPoints, LocalDate statDate, int recognizedFocusSec, int dailyCapFocusSec) {}
