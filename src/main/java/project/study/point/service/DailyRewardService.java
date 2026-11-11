package project.study.point.service;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import project.study.common.exception.ConflictException;
import project.study.common.exception.ErrorCode;
import project.study.common.exception.NotFoundException;
import project.study.planner.goal.service.PlannerGoalService;
import project.study.point.dto.ClaimResponse;
import project.study.point.dto.EarnOverviewResponse;
import project.study.point.dto.MedalClaimAllResponse;
import project.study.point.dto.MedalReward;
import project.study.point.dto.MedalRow;
import project.study.point.dto.PointReason;
import project.study.point.repository.PointRepository;
import project.study.user.service.MemberGuard;
import project.study.user.service.UserTimeZones;

/**
 * 포인트 받기 탭과 보상 받기(출석·하루 목표·메달). "오늘"은 공부 포인트와 같이 사용자 시간대의 자정 기준이다. 같은 보상은 기록의 참조 키가 같아
 * 한 번만 들어가고, 다시 요청하면 지급 없이 지금 잔액을 돌려준다.
 */
@Service
@RequiredArgsConstructor
public class DailyRewardService {

    private final PointRepository repository;
    private final PointService pointService;
    private final MemberGuard members;
    private final UserTimeZones userTimeZones;
    private final Clock clock;
    private final StudyPointPolicy studyPolicy;
    private final DailyRewardPolicy rewardPolicy;
    private final PlannerGoalService goalService;

    @Transactional(readOnly = true)
    public EarnOverviewResponse overview(Long userId) {
        members.requireMember(userId);
        LocalDate today = today(userId);
        long focusSec = repository.measuredFocusSecOn(userId, today);
        int recognized = studyPolicy.counts(today) ? studyPolicy.recognizedFocusSec(focusSec) : 0;
        Integer goalSec = goalService.get(userId, today).goalSec();
        int goalPoints = goalPoints(goalSec);
        List<MedalReward> medals = repository.unclaimedMedals(userId, studyPolicy.startsOn()).stream()
                .map(this::medalReward)
                .toList();
        return new EarnOverviewResponse(
                today,
                repository.earnedOn(userId, today),
                studyPolicy.maxDailyPoints() + rewardPolicy.attendancePoints() + goalPoints,
                new EarnOverviewResponse.Study(
                        recognized,
                        studyPolicy.dailyCapSec(),
                        studyPolicy.unitSec(),
                        studyPolicy.pointsPerUnit(),
                        repository.studyGrantedOn(userId, today),
                        studyPolicy.maxDailyPoints()),
                new EarnOverviewResponse.Attendance(
                        rewardPolicy.attendancePoints(),
                        repository.exists(userId, PointReason.ATTENDANCE, today.toString())),
                new EarnOverviewResponse.DailyGoal(
                        goalPoints,
                        goalSec,
                        focusSec,
                        goalSec == null ? null : Math.max(0, goalSec - focusSec),
                        goalPoints > 0 && focusSec >= goalSec,
                        repository.exists(userId, PointReason.DAILY_GOAL, today.toString())),
                medals,
                rewardPolicy.medalTable());
    }

    @Transactional
    public ClaimResponse claimAttendance(Long userId) {
        members.requireMember(userId);
        LocalDate today = startedToday(userId);
        return claim(userId, PointReason.ATTENDANCE, today.toString(), rewardPolicy.attendancePoints(), today);
    }

    /** 받는 포인트는 받는 순간의 목표 시간으로 정한다. 받은 뒤 목표를 올려도 더 받지 못한다. */
    @Transactional
    public ClaimResponse claimDailyGoal(Long userId) {
        members.requireMember(userId);
        LocalDate today = startedToday(userId);
        if (repository.exists(userId, PointReason.DAILY_GOAL, today.toString())) {
            // 받은 뒤 목표를 올려도 다시 요청하면 에러가 아니라 이미 받았다고 답한다
            return new ClaimResponse(false, 0, repository.balance(userId));
        }
        Integer goalSec = goalService.get(userId, today).goalSec();
        int points = goalPoints(goalSec);
        if (points <= 0 || repository.measuredFocusSecOn(userId, today) < goalSec) {
            throw new ConflictException(ErrorCode.REWARD_NOT_AVAILABLE, "아직 하루 목표를 채우지 않았습니다");
        }
        return claim(userId, PointReason.DAILY_GOAL, today.toString(), points, today);
    }

    /** 안 받은 메달을 한 번에 받는다. 받을 메달이 없으면 지급 없이 잔액만 준다. */
    @Transactional
    public MedalClaimAllResponse claimAllMedals(Long userId) {
        members.requireMember(userId);
        pointService.lock(userId);
        int count = 0;
        int total = 0;
        for (MedalRow medal : repository.unclaimedMedals(userId, studyPolicy.startsOn())) {
            int points = medalReward(medal).points();
            if (points > 0 && earnMedal(userId, medal, points)) {
                count++;
                total += points;
            }
        }
        return new MedalClaimAllResponse(count, total, repository.balance(userId));
    }

    @Transactional
    public ClaimResponse claimMedal(Long userId, long recordId) {
        members.requireMember(userId);
        MedalRow medal = repository
                .findMedal(userId, recordId)
                .filter(row -> !row.periodStart().isBefore(studyPolicy.startsOn()))
                .orElseThrow(() -> new NotFoundException(ErrorCode.MEDAL_NOT_FOUND, "받을 수 있는 메달이 아닙니다"));
        int points = medalReward(medal).points();
        if (points <= 0) {
            throw new ConflictException(ErrorCode.REWARD_NOT_AVAILABLE, "포인트가 없는 메달입니다");
        }
        boolean granted = earnMedal(userId, medal, points);
        return new ClaimResponse(granted, granted ? points : 0, repository.balance(userId));
    }

    private boolean earnMedal(long userId, MedalRow medal, int points) {
        String label = medal.boardKey() + "/" + medal.rank();
        return pointService.earn(userId, PointReason.MEDAL, "record:" + medal.recordId(), points, label, null);
    }

    private ClaimResponse claim(long userId, PointReason reason, String refKey, int points, LocalDate statDate) {
        boolean granted = pointService.earn(userId, reason, refKey, points, null, statDate);
        return new ClaimResponse(granted, granted ? points : 0, repository.balance(userId));
    }

    private int goalPoints(Integer goalSec) {
        return rewardPolicy.goalPoints(goalSec, studyPolicy.dailyCapSec());
    }

    private MedalReward medalReward(MedalRow row) {
        String[] parts = row.boardKey().split(":");
        String period = parts.length > 1 ? parts[1] : "";
        return new MedalReward(
                row.recordId(),
                row.boardKey(),
                period,
                row.periodStart(),
                row.rank(),
                rewardPolicy.medalPoints(period, row.rank()));
    }

    /** 포인트 시작 날짜 전이면 409 {@code REWARD_NOT_AVAILABLE}. 출시 전에 배포돼도 받아 가지 못하게 한다. */
    private LocalDate startedToday(long userId) {
        LocalDate today = today(userId);
        if (!studyPolicy.counts(today)) {
            throw new ConflictException(ErrorCode.REWARD_NOT_AVAILABLE, "아직 포인트를 받을 수 없습니다");
        }
        return today;
    }

    private LocalDate today(long userId) {
        return clock.instant().atZone(userTimeZones.zoneOf(userId)).toLocalDate();
    }
}
