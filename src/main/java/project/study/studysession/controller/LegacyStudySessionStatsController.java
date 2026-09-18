package project.study.studysession.controller;

import io.swagger.v3.oas.annotations.Hidden;
import java.time.LocalDate;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import project.study.studysession.dto.StudyPeriodStatsResponse;
import project.study.studysession.dto.StudySessionListResponse;
import project.study.studysession.dto.StudySessionStreakResponse;

/**
 * 구 앱(v1.2.x) 전용 통계 API — API-Version 헤더가 없거나 1인 요청만 여기로 온다 (ADR-0020).
 *
 * <p>v1.2.1 계약 그대로 쿼리 userId로 식별하고 {@link StudySessionStatsController}에 위임하는 어댑터다.
 * {@code /study-days}는 토큰 계약에서 추가된 경로라 구 앱 버전이 없다. 강제 업데이트(BY-531) 뒤 contract 시 삭제한다.
 */
// Swagger에 싣지 않는다 — springdoc은 같은 경로+메서드를 하나로 합쳐 토큰 계약(v2) 문서를 덮어쓴다.
// 구 앱 계약은 v1.2.1 그대로이며 ADR-0020의 표가 명세다
@Hidden
@RestController
@RequestMapping(value = "/api/stats", version = "1")
@RequiredArgsConstructor
public class LegacyStudySessionStatsController {

    private final StudySessionStatsController statsController;

    @GetMapping
    public StudySessionListResponse list(
            @RequestParam Long userId, @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return statsController.list(userId, date);
    }

    @GetMapping("/streak")
    public StudySessionStreakResponse streak(
            @RequestParam Long userId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return statsController.streak(userId, from, to);
    }

    @GetMapping("/period")
    public StudyPeriodStatsResponse period(
            @RequestParam Long userId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate compareFrom,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate compareTo) {
        return statsController.period(userId, from, to, compareFrom, compareTo);
    }
}
