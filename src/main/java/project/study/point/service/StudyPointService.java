package project.study.point.service;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import project.study.point.dto.PointReason;
import project.study.point.dto.StudyReward;
import project.study.point.repository.PointRepository;
import project.study.user.service.MemberGuard;
import project.study.user.service.UserTimeZones;

/**
 * 공부 포인트. 측정한 순공(직접 입력 제외)을 날짜별로 모아 "그날 받아야 할 총액 − 그날 이미 준 금액"만 지급한다. 같은 제출이 다시 오거나 자동 확정본이
 * 최종 제출로 바뀌어도 받아야 할 총액이 같으므로 두 번 주지 않는다. 한 번 지급이 빠져도 같은 날의 다음 제출이 메운다.
 */
@Service
@RequiredArgsConstructor
public class StudyPointService {

    private final PointRepository repository;
    private final PointService pointService;
    private final PointLocks locks;
    private final MemberGuard members;
    private final UserTimeZones userTimeZones;
    private final Clock clock;
    private final StudyPointPolicy policy;

    /** 세션이 저장된 직후(앱의 제출, 자동 확정) 부른다. 비회원이거나 측정 조각이 없으면 아무것도 하지 않는다. */
    @Transactional
    public void grantForSubmission(long userId, Instant submissionStartedAt) {
        if (!members.isMember(userId)) {
            return;
        }
        locks.lockUser(userId);
        Instant stored =
                repository.storedSubmissionStart(userId, submissionStartedAt).orElse(null);
        if (stored == null) {
            return;
        }
        String prefix = refKeyPrefix(stored);
        // 이 제출의 지금 날짜와 받았던 날짜, 그리고 그 앞뒤 하루를 함께 맞춘다. 자동 확정본이 받은 뒤 최종 제출이 다른 시간대로 오면 날짜가
        // 하루까지 밀리는데, 옛 날짜의 포인트가 같은 날 다른 제출 이름으로 들어가 있을 수 있어 제출이 아니라 날짜로 찾는다
        Set<LocalDate> dates = new TreeSet<>();
        Set<LocalDate> own =
                new TreeSet<>(repository.studyGrantedByDate(userId, prefix).keySet());
        own.addAll(repository.measuredDatesOf(userId, stored));
        own.forEach(date -> dates.addAll(List.of(date.minusDays(1), date, date.plusDays(1))));
        Map<LocalDate, Integer> shortBy = new LinkedHashMap<>();
        Map<LocalDate, Integer> overBy = new LinkedHashMap<>();
        for (LocalDate date : dates) {
            int diff = policy.targetPoints(recognizedFocusSec(userId, date)) - repository.studyGrantedOn(userId, date);
            if (diff > 0 && own.contains(date)) {
                shortBy.put(date, diff);
            } else if (diff < 0) {
                overBy.put(date, -diff);
            }
        }
        shortBy.forEach((date, shortage) -> {
            int left = moveFromOverGranted(userId, prefix, overBy, date, shortage);
            if (left > 0) {
                // 그때까지 준 금액을 키에 넣어, 같은 제출·날짜가 차액을 한 번 더 받아도 줄이 겹치지 않게 한다
                String refKey = prefix + date + "/" + repository.studyGrantedOn(userId, date);
                pointService.earn(userId, PointReason.STUDY, refKey, left, null, date);
            }
        });
    }

    /** 더 받은 날짜의 금액을 모자란 날짜로 옮긴다(잔액은 그대로). 옮기고도 모자란 금액을 돌려준다. */
    private int moveFromOverGranted(
            long userId, String prefix, Map<LocalDate, Integer> overBy, LocalDate to, int shortage) {
        int left = shortage;
        for (Map.Entry<LocalDate, Integer> over : overBy.entrySet()) {
            int amount = Math.min(left, over.getValue());
            if (amount <= 0) {
                continue;
            }
            String refKey = prefix + "move/" + UUID.randomUUID();
            repository.insertLedger(userId, -amount, PointReason.STUDY_ADJUST, refKey + "/from", null, over.getKey());
            repository.insertLedger(userId, amount, PointReason.STUDY_ADJUST, refKey + "/to", null, to);
            over.setValue(over.getValue() - amount);
            left -= amount;
        }
        return left;
    }

    /** 그 제출이 받은 포인트와 그 제출 날짜(제출이 없으면 오늘)의 인정 순공. */
    @Transactional(readOnly = true)
    public StudyReward reward(Long userId, Instant submissionStartedAt) {
        members.requireMember(userId);
        Instant stored =
                repository.storedSubmissionStart(userId, submissionStartedAt).orElse(submissionStartedAt);
        LocalDate date = repository.lastDateOf(userId, stored);
        if (date == null) {
            date = clock.instant().atZone(userTimeZones.zoneOf(userId)).toLocalDate();
        }
        return new StudyReward(
                repository.studyGrantedFor(userId, refKeyPrefix(stored)),
                date,
                recognizedFocusSec(userId, date),
                policy.dailyCapSec());
    }

    private int recognizedFocusSec(long userId, LocalDate date) {
        if (!policy.counts(date)) {
            return 0;
        }
        return policy.recognizedFocusSec(repository.measuredFocusSecOn(userId, date));
    }

    private static String refKeyPrefix(Instant submissionStartedAt) {
        return submissionStartedAt + "/";
    }
}
