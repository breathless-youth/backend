package project.study.group.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;
import static project.study.group.support.GroupDeskTestConfig.NOW;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;
import project.study.block.service.BlockService;
import project.study.group.push.GroupNotifications;
import project.study.group.repository.GroupAlertRepository;
import project.study.group.repository.GroupAlertRepository.AlertGroup;
import project.study.group.repository.GroupAlertRepository.StudyStart;
import project.study.group.repository.GroupDeskQueries;
import project.study.group.repository.GroupDeskQueries.MemberProfile;
import project.study.group.repository.GroupMemberRepository;
import project.study.group.repository.GroupMemberRepository.MemberRow;

/**
 * 시작한 사람 한 명의 처리가 실패해도 같은 틱의 다른 사람 알림은 나간다.
 */
class GroupStartAlertsIsolationTest {

    private static final long BROKEN = 1;
    private static final long HEALTHY = 2;
    private static final long GROUP = 10;
    private static final long RECEIVER = 20;

    private final GroupMemberRepository members = mock(GroupMemberRepository.class);
    private final GroupAlertRepository alerts = mock(GroupAlertRepository.class);
    private final GroupDeskQueries deskQueries = mock(GroupDeskQueries.class);
    private final GroupNotifications notifications = mock(GroupNotifications.class);
    private final TransactionTemplate tx = mock(TransactionTemplate.class);
    private final GroupStartAlerts startAlerts =
            new GroupStartAlerts(members, alerts, deskQueries, notifications, mock(BlockService.class), tx);

    @Test
    void 시작을_가져온_사이_연결이_끊기면_다시_확인할_수_있다() {
        StudyStart start = new StudyStart(1, 100, HEALTHY);
        when(tx.execute(any()))
                .thenAnswer(invocation -> invocation
                        .<TransactionCallback<?>>getArgument(0)
                        .doInTransaction(mock(TransactionStatus.class)));
        when(alerts.claimStudyStarts()).thenReturn(List.of(start));
        when(alerts.findStudyGroup(start)).thenReturn(Optional.of(new AlertGroup(GROUP, "스터디", false)));

        assertThat(startAlerts.process(NOW)).isZero();

        verify(alerts).releaseStudyStart(start.participationId());
        verifyNoMoreInteractions(notifications);
    }

    @Test
    void 한_사람의_처리가_실패해도_다음_사람의_알림은_나간다() {
        when(tx.execute(any()))
                .thenAnswer(invocation -> invocation
                        .<TransactionCallback<?>>getArgument(0)
                        .doInTransaction(mock(TransactionStatus.class)));
        StudyStart broken = new StudyStart(1, 100, BROKEN);
        StudyStart healthy = new StudyStart(2, 200, HEALTHY);
        when(alerts.claimStudyStarts()).thenReturn(List.of(broken, healthy));
        when(alerts.findStudyGroup(broken)).thenThrow(new IllegalStateException("db hiccup"));
        when(alerts.findStudyGroup(healthy)).thenReturn(Optional.of(new AlertGroup(GROUP, "스터디", true)));
        when(members.findLiveByGroups(List.of(GROUP)))
                .thenReturn(List.of(new MemberRow(1, GROUP, RECEIVER, NOW, "APPLICATION", true)));
        when(alerts.findSeatHolders(healthy.roomId())).thenReturn(List.of(HEALTHY));
        when(deskQueries.profiles(List.of(HEALTHY))).thenReturn(Map.of(HEALTHY, new MemberProfile("공부왕", null)));
        when(alerts.claim(List.of(RECEIVER), GROUP, NOW)).thenReturn(List.of(RECEIVER));

        int sent = startAlerts.process(NOW);

        assertThat(sent).isEqualTo(1);
        verify(notifications).memberStarted(List.of(RECEIVER), GROUP, "스터디", "공부왕");
        verifyNoMoreInteractions(notifications);
    }
}
