package project.study.group.push;

import io.sentry.Sentry;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executor;
import lombok.extern.slf4j.Slf4j;
import project.study.common.category.StudyCategory;
import project.study.common.tx.AfterCommit;
import project.study.push.dto.PushMessage;
import project.study.push.service.PushSender;

/**
 * 그룹 푸시 8종 (BY-1124). 문구는 BY-1124 표 그대로이고, {그룹 이름}·{닉네임}은 값을 그대로 끼운다.
 *
 * <p>업무 트랜잭션이 커밋된 뒤에만({@link AfterCommit}) 별도 실행기로 넘겨 보낸다 — 롤백되면 나가지 않고, FCM 호출이 요청 스레드를 붙잡지
 * 않는다. 발송 예외는 로그와 Sentry에만 남기고 던지지 않는다. {@code push_id}는 매번 새로 만든다(발송 기록 테이블을 쓰지 않는다).
 */
@Slf4j
public class GroupNotifications {

    static final String APPLICATION_RECEIVED = "group_application_received";
    static final String OWNER_TRANSFERRED = "group_owner_transferred";
    static final String APPLICATION_ACCEPTED = "group_application_accepted";
    static final String APPLICATION_DECLINED = "group_application_declined";
    static final String APPLICATION_EXPIRED = "group_application_expired";
    static final String APPLICATION_ROOM_CLOSED = "group_application_room_closed";
    static final String MEMBER_STARTED = "group_member_started";
    static final String MEMBER_KICKED = "group_member_kicked";

    private final PushSender pushSender;
    private final Executor executor;

    public GroupNotifications(PushSender pushSender, Executor executor) {
        this.pushSender = pushSender;
        this.executor = executor;
    }

    /** 그룹장에게: 새 신청이 왔다. */
    public void applicationReceived(long ownerId, long groupId, String groupName, String applicantNickname) {
        send(
                List.of(ownerId),
                "새 신청을 확인해 볼까요?",
                applicantNickname + "님이 " + groupName + "에 신청했어요",
                GroupPushLinks.applications(groupId),
                APPLICATION_RECEIVED);
    }

    /** 새 그룹장에게: 그룹장을 넘겨받았다. */
    public void ownerTransferred(long newOwnerId, long groupId, String groupName) {
        send(
                List.of(newOwnerId),
                "그룹장이 됐어요",
                groupName + "의 그룹장을 넘겨받았어요",
                GroupPushLinks.group(groupId),
                OWNER_TRANSFERRED);
    }

    /** 신청한 사람에게: 수락돼 멤버가 됐다. */
    public void applicationAccepted(long userId, long groupId, String groupName) {
        send(
                List.of(userId),
                "새로운 그룹에서 공부를 시작해 볼까요?",
                groupName + "의 멤버가 됐어요",
                GroupPushLinks.group(groupId),
                APPLICATION_ACCEPTED);
    }

    /** 신청한 사람에게: 거절됐다(신청이 신고돼 거절된 경우도 같다). */
    public void applicationDeclined(long userId, String groupName, StudyCategory category) {
        send(
                List.of(userId),
                "비슷한 그룹에 신청해 볼까요?",
                groupName + " 신청이 거절됐어요",
                GroupPushLinks.publicTab(category),
                APPLICATION_DECLINED);
    }

    /** 신청한 사람에게: 답이 없어 만료됐다. */
    public void applicationExpired(long userId, String groupName) {
        send(
                List.of(userId),
                "다른 그룹에 신청해 볼까요?",
                groupName + " 신청은 답이 없어 취소됐어요",
                GroupPushLinks.publicTab(),
                APPLICATION_EXPIRED);
    }

    /** 신청한 사람에게: 그룹이 사라져 신청이 취소됐다. */
    public void applicationRoomClosed(long userId, String groupName) {
        send(
                List.of(userId),
                "다른 그룹을 찾아볼까요?",
                groupName + " 그룹이 사라져 신청이 취소됐어요",
                GroupPushLinks.publicTab(),
                APPLICATION_ROOM_CLOSED);
    }

    /** 알림을 켠 다른 멤버들에게: 멤버가 공부를 시작했다. */
    public void memberStarted(Collection<Long> receiverIds, long groupId, String groupName, String starterNickname) {
        send(
                receiverIds,
                starterNickname + "님이 공부를 시작했어요",
                groupName + "에 같이 공부하러 가 볼까요?",
                GroupPushLinks.group(groupId),
                MEMBER_STARTED);
    }

    /** 내보내진 사람에게. */
    public void memberKicked(long userId, String groupName) {
        send(List.of(userId), "다른 그룹과 다시 시작해 볼까요?", groupName + "에서 내보내졌어요", GroupPushLinks.publicTab(), MEMBER_KICKED);
    }

    private void send(Collection<Long> receiverIds, String title, String body, String link, String type) {
        if (receiverIds.isEmpty()) {
            return;
        }
        // 커밋 뒤에 실행기가 읽는 동안 호출자가 컬렉션을 바꾸지 못하게 복사해 둔다
        List<Long> receivers = List.copyOf(receiverIds);
        PushMessage message = new PushMessage(title, body, link, type, UUID.randomUUID());
        AfterCommit.run(() -> executor.execute(() -> deliver(receivers, message)));
    }

    private void deliver(List<Long> receivers, PushMessage message) {
        try {
            pushSender.send(receivers, message);
        } catch (Exception e) {
            log.error("그룹 푸시 발송 실패 type={}", message.type(), e);
            Sentry.captureException(e);
        }
    }
}
