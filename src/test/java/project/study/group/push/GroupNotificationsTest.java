package project.study.group.push;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.util.Collection;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import project.study.common.category.StudyCategory;
import project.study.common.tx.AfterCommit;
import project.study.push.dto.PushMessage;
import project.study.push.service.PushSender;

/** 푸시 문구는 BY-1124 표 그대로여야 한다 — type·제목·본문·link를 글자까지 맞춰 본다. 동기 실행기를 쓴다. */
class GroupNotificationsTest {

    private PushSender pushSender;
    private GroupNotifications notifications;

    @BeforeEach
    void setUp() {
        pushSender = mock(PushSender.class);
        notifications = new GroupNotifications(pushSender, Runnable::run);
    }

    @AfterEach
    void clearSynchronization() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void 새_신청은_그룹장에게_신청_목록_링크로_간다() {
        notifications.applicationReceived(7L, 10L, "새벽 공시반", "포메");

        PushMessage message = sentTo(List.of(7L));
        assertThat(message.type()).isEqualTo("group_application_received");
        assertThat(message.title()).isEqualTo("새 신청을 확인해 볼까요?");
        assertThat(message.body()).isEqualTo("포메님이 새벽 공시반에 신청했어요");
        assertThat(message.link()).isEqualTo("/groups/10/applications");
    }

    @Test
    void 그룹장을_넘겨받으면_새_그룹장에게_그룹_홈_링크로_간다() {
        notifications.ownerTransferred(8L, 10L, "새벽 공시반");

        PushMessage message = sentTo(List.of(8L));
        assertThat(message.type()).isEqualTo("group_owner_transferred");
        assertThat(message.title()).isEqualTo("그룹장이 됐어요");
        assertThat(message.body()).isEqualTo("새벽 공시반의 그룹장을 넘겨받았어요");
        assertThat(message.link()).isEqualTo("/groups/10");
    }

    @Test
    void 신청이_수락되면_신청한_사람에게_그룹_홈_링크로_간다() {
        notifications.applicationAccepted(9L, 10L, "새벽 공시반");

        PushMessage message = sentTo(List.of(9L));
        assertThat(message.type()).isEqualTo("group_application_accepted");
        assertThat(message.title()).isEqualTo("새로운 그룹에서 공부를 시작해 볼까요?");
        assertThat(message.body()).isEqualTo("새벽 공시반의 멤버가 됐어요");
        assertThat(message.link()).isEqualTo("/groups/10");
    }

    @Test
    void 신청이_거절되면_같은_시험_목표의_공개_그룹_링크로_간다() {
        notifications.applicationDeclined(9L, "새벽 공시반", StudyCategory.CIVIL_SERVICE);

        PushMessage message = sentTo(List.of(9L));
        assertThat(message.type()).isEqualTo("group_application_declined");
        assertThat(message.title()).isEqualTo("비슷한 그룹에 신청해 볼까요?");
        assertThat(message.body()).isEqualTo("새벽 공시반 신청이 거절됐어요");
        assertThat(message.link()).isEqualTo("/groups?tab=public&category=CIVIL_SERVICE");
    }

    @Test
    void 신청이_만료되면_공개_그룹_탭_링크로_간다() {
        notifications.applicationExpired(9L, "새벽 공시반");

        PushMessage message = sentTo(List.of(9L));
        assertThat(message.type()).isEqualTo("group_application_expired");
        assertThat(message.title()).isEqualTo("다른 그룹에 신청해 볼까요?");
        assertThat(message.body()).isEqualTo("새벽 공시반 신청은 답이 없어 취소됐어요");
        assertThat(message.link()).isEqualTo("/groups?tab=public");
    }

    @Test
    void 그룹이_사라져_신청이_취소되면_공개_그룹_탭_링크로_간다() {
        notifications.applicationRoomClosed(9L, "새벽 공시반");

        PushMessage message = sentTo(List.of(9L));
        assertThat(message.type()).isEqualTo("group_application_room_closed");
        assertThat(message.title()).isEqualTo("다른 그룹을 찾아볼까요?");
        assertThat(message.body()).isEqualTo("새벽 공시반 그룹이 사라져 신청이 취소됐어요");
        assertThat(message.link()).isEqualTo("/groups?tab=public");
    }

    @Test
    void 멤버가_공부를_시작하면_알림_켠_멤버들에게_그룹_홈_링크로_간다() {
        notifications.memberStarted(List.of(1L, 2L, 3L), 10L, "새벽 공시반", "포메");

        PushMessage message = sentTo(List.of(1L, 2L, 3L));
        assertThat(message.type()).isEqualTo("group_member_started");
        assertThat(message.title()).isEqualTo("포메님이 공부를 시작했어요");
        assertThat(message.body()).isEqualTo("새벽 공시반에 같이 공부하러 가 볼까요?");
        assertThat(message.link()).isEqualTo("/groups/10");
    }

    @Test
    void 받을_사람이_없으면_보내지_않는다() {
        notifications.memberStarted(List.of(), 10L, "새벽 공시반", "포메");

        verifyNoInteractions(pushSender);
    }

    @Test
    void 내보내지면_공개_그룹_탭_링크로_간다() {
        notifications.memberKicked(9L, "새벽 공시반");

        PushMessage message = sentTo(List.of(9L));
        assertThat(message.type()).isEqualTo("group_member_kicked");
        assertThat(message.title()).isEqualTo("다른 그룹과 다시 시작해 볼까요?");
        assertThat(message.body()).isEqualTo("새벽 공시반에서 내보내졌어요");
        assertThat(message.link()).isEqualTo("/groups?tab=public");
    }

    @Test
    void push_id는_보낼_때마다_새로_만든다() {
        notifications.memberKicked(9L, "새벽 공시반");
        notifications.memberKicked(9L, "새벽 공시반");

        ArgumentCaptor<PushMessage> captor = ArgumentCaptor.forClass(PushMessage.class);
        verify(pushSender, org.mockito.Mockito.times(2)).send(any(), captor.capture());
        assertThat(captor.getAllValues().get(0).pushId()).isNotNull();
        assertThat(captor.getAllValues().get(0).pushId())
                .isNotEqualTo(captor.getAllValues().get(1).pushId());
    }

    @Test
    void 트랜잭션_안에서는_커밋_뒤에_보낸다() {
        TransactionSynchronizationManager.initSynchronization();

        notifications.memberKicked(9L, "새벽 공시반");

        verifyNoInteractions(pushSender);
        TransactionSynchronizationManager.getSynchronizations().forEach(TransactionSynchronization::afterCommit);
        sentTo(List.of(9L));
    }

    @Test
    void 커밋_뒤_작업_안에서_부른_알림도_나간다() {
        TransactionSynchronizationManager.initSynchronization();

        // 그룹장 넘기기처럼 커밋 뒤 작업 하나가 알림과 다른 발송을 함께 부르는 경우
        AfterCommit.run(() -> {
            notifications.ownerTransferred(8L, 10L, "새벽 공시반");
            notifications.memberKicked(9L, "새벽 공시반");
        });
        verifyNoInteractions(pushSender);
        TransactionSynchronizationManager.getSynchronizations().forEach(TransactionSynchronization::afterCommit);

        ArgumentCaptor<PushMessage> message = ArgumentCaptor.forClass(PushMessage.class);
        verify(pushSender, org.mockito.Mockito.times(2)).send(any(), message.capture());
        assertThat(message.getAllValues())
                .extracting(PushMessage::type)
                .containsExactly("group_owner_transferred", "group_member_kicked");
    }

    @Test
    void 롤백되면_보내지_않는다() {
        TransactionSynchronizationManager.initSynchronization();

        notifications.memberKicked(9L, "새벽 공시반");
        TransactionSynchronizationManager.getSynchronizations()
                .forEach(sync -> sync.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));

        verifyNoInteractions(pushSender);
    }

    @Test
    void 발송이_실패해도_호출자에게_퍼지지_않는다() {
        doThrow(new IllegalStateException("FCM 장애")).when(pushSender).send(any(), any());

        assertThatCode(() -> notifications.memberKicked(9L, "새벽 공시반")).doesNotThrowAnyException();
    }

    private PushMessage sentTo(Collection<Long> expectedReceivers) {
        ArgumentCaptor<Collection<Long>> receivers = ArgumentCaptor.captor();
        ArgumentCaptor<PushMessage> message = ArgumentCaptor.forClass(PushMessage.class);
        verify(pushSender).send(receivers.capture(), message.capture());
        assertThat(receivers.getValue()).containsExactlyElementsOf(expectedReceivers);
        assertThat(message.getValue().pushId()).isNotNull();
        return message.getValue();
    }
}
