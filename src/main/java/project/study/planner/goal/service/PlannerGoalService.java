package project.study.planner.goal.service;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import project.study.common.exception.BadRequestException;
import project.study.planner.goal.dto.PlannerDefaultGoalResponse;
import project.study.planner.goal.dto.PlannerGoalRequest;
import project.study.planner.goal.dto.PlannerGoalResponse;
import project.study.planner.goal.repository.PlannerGoalRepository;
import project.study.planner.service.PlannerCalendar;
import project.study.subject.repository.StudySubjectLockRepository;
import project.study.user.service.UserTimeZones;

/** 개별 목표를 기본값과 분리해 저장한다. 기본값 변경은 개별 목표를 덮어쓰지 않는다. */
@Service
@RequiredArgsConstructor
public class PlannerGoalService {

    private final PlannerGoalRepository repository;
    private final Clock clock;
    private final UserTimeZones userTimeZones;
    private final StudySubjectLockRepository locks;

    @Transactional(readOnly = true)
    public PlannerGoalResponse get(Long userId, LocalDate date) {
        PlannerCalendar.checkDate(date);
        Integer individual = repository.findIndividual(userId, date).orElse(null);
        Integer defaultGoal = repository.findDefault(userId).orElse(null);
        return new PlannerGoalResponse(individual != null ? individual : defaultGoal, individual, defaultGoal);
    }

    /** 목표는 달력 날짜 값이다. 조회 요청으로 기기 시간대 설정을 바꾸지 않는다. */
    @Transactional(readOnly = true)
    public PlannerGoalResponse get(Long userId, LocalDate date, String timeZone) {
        return get(userId, date);
    }

    @Transactional(readOnly = true)
    public PlannerDefaultGoalResponse getDefault(Long userId, String timeZone) {
        return new PlannerDefaultGoalResponse(repository.findDefault(userId).orElse(null));
    }

    @Transactional
    public PlannerGoalResponse save(Long userId, LocalDate date, PlannerGoalRequest request, String timeZone) {
        validateGoal(request.goalSec());
        locks.lockUser(userId);
        requireFuture(date, userTimeZones.resolve(userId, timeZone));
        repository.upsertIndividual(userId, date, request.goalSec());
        Integer defaultGoal = repository.findDefault(userId).orElse(null);
        return new PlannerGoalResponse(request.goalSec(), request.goalSec(), defaultGoal);
    }

    @Transactional
    public PlannerDefaultGoalResponse saveDefault(Long userId, PlannerGoalRequest request, String timeZone) {
        validateGoal(request.goalSec());
        locks.lockUser(userId);
        userTimeZones.resolve(userId, timeZone);
        repository.upsertDefault(userId, request.goalSec());
        return new PlannerDefaultGoalResponse(request.goalSec());
    }

    @Transactional
    public void delete(Long userId, LocalDate date, String timeZone) {
        locks.lockUser(userId);
        requireFuture(date, userTimeZones.resolve(userId, timeZone));
        repository.deleteIndividual(userId, date);
    }

    @Transactional
    public void deleteDefault(Long userId, String timeZone) {
        locks.lockUser(userId);
        userTimeZones.resolve(userId, timeZone);
        repository.deleteDefault(userId);
    }

    private void requireFuture(LocalDate date, ZoneId zone) {
        PlannerCalendar.checkDate(date);
        LocalDate today = PlannerCalendar.today(clock, zone);
        if (!date.isAfter(today)) {
            throw new BadRequestException("개별 목표는 사용자 현지 05시 기준 오늘보다 미래 날짜만 변경할 수 있습니다");
        }
    }

    private void validateGoal(Integer goalSec) {
        if (goalSec == null || goalSec < 0 || goalSec > 86400 || goalSec % 1800 != 0) {
            throw new BadRequestException("목표 초는 0~86400 범위의 1800초 단위여야 합니다");
        }
    }
}
