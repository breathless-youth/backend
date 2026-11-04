package project.study.group.scheduler;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static project.study.group.support.GroupDeskTestConfig.NOW;

import java.time.Clock;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import project.study.group.service.GroupStartAlerts;

/** 스케줄러는 시계로 지금을 읽어 시작 알림 작업만 부른다 — 실패는 밖으로 내보내지 않는다. */
class GroupStartAlertSchedulerTest {

    private final GroupStartAlerts startAlerts = mock(GroupStartAlerts.class);
    private final GroupStartAlertScheduler scheduler =
            new GroupStartAlertScheduler(startAlerts, Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void 시계의_지금으로_시작_알림_작업을_부른다() {
        scheduler.alertNewlyStartedMembers();

        verify(startAlerts).process(NOW);
    }

    @Test
    void 작업이_실패해도_예외를_밖으로_내지_않는다() {
        doThrow(new IllegalStateException("db down")).when(startAlerts).process(NOW);

        assertThatCode(scheduler::alertNewlyStartedMembers).doesNotThrowAnyException();
    }
}
