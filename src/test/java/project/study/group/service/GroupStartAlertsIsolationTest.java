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
import org.junit.jupiter.api.Test;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;
import project.study.block.service.BlockService;
import project.study.common.category.StudyCategory;
import project.study.group.GroupVisibility;
import project.study.group.push.GroupNotifications;
import project.study.group.repository.GroupAlertRepository;
import project.study.group.repository.GroupDeskQueries;
import project.study.group.repository.GroupDeskQueries.MemberProfile;
import project.study.group.repository.GroupMemberRepository;
import project.study.group.repository.GroupMemberRepository.MemberRow;
import project.study.group.repository.GroupRepository;
import project.study.group.repository.GroupRepository.GroupRow;
import project.study.studysession.dto.NewDraft;
import project.study.studysession.service.NewDraftSource;

/**
 * 시작한 사람 한 명의 처리가 실패해도 같은 틱의 다른 사람 알림은 나간다 (BY-1124). 꺼낸 draft는 이미 표시됐으므로 한 사람의 이상이 매 틱 되풀이되는
 * 독약이 되지 않는다는 것도 함께 확인한다.
 */
class GroupStartAlertsIsolationTest {

    private static final long BROKEN = 1;
    private static final long HEALTHY = 2;
    private static final long GROUP = 10;
    private static final long RECEIVER = 20;

    private final NewDraftSource newDrafts = mock(NewDraftSource.class);
    private final GroupMemberRepository members = mock(GroupMemberRepository.class);
    private final GroupRepository groups = mock(GroupRepository.class);
    private final GroupAlertRepository alerts = mock(GroupAlertRepository.class);
    private final GroupDeskQueries deskQueries = mock(GroupDeskQueries.class);
    private final GroupNotifications notifications = mock(GroupNotifications.class);
    private final TransactionTemplate tx = mock(TransactionTemplate.class);
    private final GroupStartAlerts startAlerts = new GroupStartAlerts(
            newDrafts, members, groups, alerts, deskQueries, notifications, mock(BlockService.class), tx);

    @Test
    void 한_사람의_처리가_실패해도_다음_사람의_알림은_나간다() {
        when(tx.execute(any()))
                .thenAnswer(invocation -> invocation
                        .<TransactionCallback<?>>getArgument(0)
                        .doInTransaction(mock(TransactionStatus.class)));
        when(newDrafts.claimNewDrafts(NOW)).thenReturn(List.of(new NewDraft(BROKEN, NOW), new NewDraft(HEALTHY, NOW)));
        when(members.findLiveGroupIdsOfUser(BROKEN)).thenThrow(new IllegalStateException("db hiccup"));
        when(members.findLiveGroupIdsOfUser(HEALTHY)).thenReturn(List.of(GROUP));
        when(groups.findLiveByIds(List.of(GROUP)))
                .thenReturn(List.of(new GroupRow(
                        GROUP, "스터디", null, GroupVisibility.PUBLIC, StudyCategory.ETC, 99, null, NOW, NOW, null)));
        when(members.findLiveByGroups(List.of(GROUP)))
                .thenReturn(List.of(new MemberRow(1, GROUP, RECEIVER, NOW, "APPLICATION", true)));
        when(alerts.findSeatHolders(List.of(GROUP))).thenReturn(Map.of());
        when(deskQueries.profiles(List.of(HEALTHY))).thenReturn(Map.of(HEALTHY, new MemberProfile("공부왕", null)));
        when(alerts.claim(List.of(RECEIVER), GROUP, NOW)).thenReturn(List.of(RECEIVER));

        int sent = startAlerts.process(NOW);

        assertThat(sent).isEqualTo(1);
        verify(notifications).memberStarted(List.of(RECEIVER), GROUP, "스터디", "공부왕");
        verifyNoMoreInteractions(notifications);
    }
}
