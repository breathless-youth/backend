package project.study.point.service;

import java.time.Clock;
import java.time.LocalDate;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import project.study.common.exception.BadRequestException;
import project.study.common.exception.NotFoundException;
import project.study.point.dto.PointReason;
import project.study.point.repository.PointRepository;
import project.study.user.service.MemberGuard;
import project.study.user.service.UserTimeZones;

/**
 * 광고 보상. 앱이 "봤다"고 알리는 요청은 믿지 않고, AdMob이 시청을 확인한 뒤 서버로 직접 보내는 콜백으로만 지급한다. 앱은 광고를 띄울 때 서버
 * 확인 옵션의 user_id에 사용자 ID를 넣는다.
 *
 * <p>서명이 맞는 콜백에는 지급하지 않을 때도 200으로 답한다 — 200이 아니면 구글이 같은 콜백을 다시 보내는데, 한도 초과나 없는 사용자는 다시
 * 받아도 결과가 같다. 같은 콜백(transaction_id)은 기록의 참조 키로 한 번만 들어간다. 하루 횟수는 콜백이 도착한 시각의 사용자 날짜로 센다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdRewardService {

    private static final int MAX_TRANSACTION_ID_LENGTH = 100;

    private final AdMobSsvVerifier verifier;
    private final AdRewardPolicy policy;
    private final StudyPointPolicy studyPolicy;
    private final PointService pointService;
    private final PointRepository repository;
    private final MemberGuard members;
    private final UserTimeZones userTimeZones;
    private final Clock clock;

    /** 서명을 확인하고 서명된 값을 준다. 키 서버를 부를 수 있어 DB 트랜잭션 밖에서 한다. */
    public Map<String, String> verified(String rawQuery) {
        if (!policy.available()) {
            throw new NotFoundException("광고 보상이 설정되지 않았습니다");
        }
        return verifier.verify(rawQuery).orElseThrow(() -> new BadRequestException("서명을 확인할 수 없습니다"));
    }

    /** params는 {@link #verified}가 준 값이어야 한다. */
    @Transactional
    public void grant(Map<String, String> params) {
        String adUnit = params.get("ad_unit");
        String transactionId = params.get("transaction_id");
        if (!policy.owns(adUnit)) {
            log.warn("우리 광고 단위가 아닌 광고 보상 콜백: ad_unit={}", adUnit);
            return;
        }
        Long userId = userId(params.get("user_id"));
        if (userId == null
                || transactionId == null
                || transactionId.isBlank()
                || transactionId.length() > MAX_TRANSACTION_ID_LENGTH
                || !members.isMember(userId)) {
            log.warn("지급할 수 없는 광고 보상 콜백: transaction_id={}", transactionId);
            return;
        }
        LocalDate today = clock.instant().atZone(userTimeZones.zoneOf(userId)).toLocalDate();
        if (!studyPolicy.counts(today)) {
            return;
        }
        pointService.lock(userId);
        if (repository.countOn(userId, PointReason.AD, today) >= policy.dailyLimit()) {
            return;
        }
        pointService.earn(userId, PointReason.AD, "admob:" + transactionId, policy.points(), null, today);
    }

    private static Long userId(String value) {
        try {
            return value == null ? null : Long.valueOf(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
