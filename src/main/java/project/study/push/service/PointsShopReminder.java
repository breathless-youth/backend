package project.study.push.service;

import io.sentry.Sentry;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import project.study.common.time.DefaultTimeZone;
import project.study.mission.service.HomeMissionRewardService;
import project.study.mission.service.HomeMissionService;
import project.study.point.service.PointService;
import project.study.point.service.PointStart;
import project.study.push.dto.PushMessage;
import project.study.push.dto.PushResult;
import project.study.push.dto.PushText;
import project.study.push.repository.PointsShopReminderQueries;
import project.study.push.repository.PushExperimentQueries;

@Slf4j
@Service
@RequiredArgsConstructor
public class PointsShopReminder {

    // 홈 탭이 포인트샵 같은 하위 화면에 머물고 있어도 미션이 있는 홈을 새로 연다.
    private static final String MISSIONS_LINK = "/home?from=points_shop_reminder";

    private final PointsShopReminderQueries queries;
    private final PushExperimentQueries sendLog;
    private final PushSender pushSender;
    private final HomeMissionService missions;
    private final HomeMissionRewardService rewards;
    private final PointService points;
    private final PointStart pointStart;

    public void run(Instant now) {
        if (!pushSender.isEnabled()) {
            return;
        }
        LocalDate today = now.atZone(DefaultTimeZone.ZONE).toLocalDate();
        LocalDate shopStart = pointStart.startsOn();
        if (shopStart == null || today.isBefore(shopStart)) {
            return;
        }
        List<Integer> stepPoints = rewards.stepPoints(true);
        for (long userId : queries.findDue(today)) {
            try {
                sendIfEligible(userId, today, now, stepPoints);
            } catch (RuntimeException e) {
                log.error("포인트샵 리마인드 발송 실패 (userId={})", userId, e);
                Sentry.captureException(e);
            }
        }
    }

    private void sendIfEligible(long userId, LocalDate today, Instant now, List<Integer> stepPoints) {
        int step = missions.completedSteps(userId, true);
        if (step >= 3 || missions.hasPurchasedAccessory(userId)) {
            return;
        }
        int balance = points.balanceOf(userId);
        long budget =
                switch (step) {
                    case 0 -> (long) balance + stepPoints.get(0) + stepPoints.get(1);
                    case 1 -> (long) balance + stepPoints.get(1) + rewards.claimablePoints(userId, 1);
                    default -> balance;
                };
        Optional<Long> affordableItem = queries.affordableAccessory(userId, budget);
        if (affordableItem.isEmpty()) {
            return;
        }
        UUID pushId = UUID.randomUUID();
        PushMessage message = messageFor(step, balance, stepPoints.get(2), affordableItem.get(), pushId);
        if (sendLog.reserveSend(pushId, userId, message.type(), today, now)) {
            PushResult result = pushSender.sendToEnabledDevices(List.of(userId), message);
            sendLog.recordAccepted(pushId, result.sent());
        }
    }

    private static PushMessage messageFor(int step, int balance, int reward, long itemId, UUID pushId) {
        return switch (step) {
            case 0 ->
                new PushMessage(
                        new PushText("포인트를 모아 캐릭터를 꾸며보세요", "지금 미션을 시작하면 소품을 얻을 수 있어요!"),
                        new PushText("Earn points and dress up your mascot", "Start the missions to get an accessory!"),
                        MISSIONS_LINK,
                        "points_shop_reminder_not_started",
                        pushId);
            case 1 ->
                new PushMessage(
                        new PushText("인기 소품 구매까지 미션 하나 남았어요", "5분만 공부하면 소품을 살 수 있어요!"),
                        new PushText(
                                "One mission to go to get an accessory", "Study for 5 minutes to earn enough points!"),
                        MISSIONS_LINK,
                        "points_shop_reminder_one_step",
                        pushId);
            default ->
                new PushMessage(
                        new PushText(
                                String.format(Locale.KOREA, "가지고 있는 %,dP로 인기 소품을 구매해보세요", balance),
                                String.format(Locale.KOREA, "첫 소품을 구매하면 %,dP를 추가로 받아요!", reward)),
                        new PushText(
                                String.format(Locale.US, "Shop for accessories with your %,d points", balance),
                                String.format(
                                        Locale.US, "Get %,d bonus points when you buy your first accessory!", reward)),
                        "/points/accessories?item=" + itemId,
                        "points_shop_reminder_two_steps",
                        pushId);
        };
    }
}
