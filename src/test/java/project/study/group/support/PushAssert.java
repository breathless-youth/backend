package project.study.group.support;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

import java.util.List;
import org.mockito.ArgumentCaptor;
import project.study.push.dto.PushMessage;
import project.study.push.service.PushSender;

/**
 * 그룹 푸시 단언 (BY-1124). 푸시는 커밋 뒤 별도 실행기에서 나가 요청이 끝난 직후에는 아직 안 나갔을 수 있다 — 나갔는지는 기다리며 보고,
 * 안 나갔는지는 잠깐 더 지켜본 뒤 본다.
 */
public final class PushAssert {

    private static final int WAIT_MS = 3000;
    private static final int SETTLE_MS = 300;

    private PushAssert() {}

    /** 이 사람에게 푸시가 나갈 때까지 기다린 뒤, 더 나가지 않는지 지켜보고, 나간 푸시를 돌려준다. */
    public static PushMessage pushedOnceTo(PushSender sender, long userId) {
        ArgumentCaptor<PushMessage> captor = ArgumentCaptor.forClass(PushMessage.class);
        verify(sender, timeout(WAIT_MS)).send(eq(List.of(userId)), captor.capture());
        verify(sender, after(SETTLE_MS).times(1)).send(eq(List.of(userId)), any());
        return captor.getValue();
    }

    /** 이 사람에게는 푸시가 나가지 않는다. */
    public static void notPushedTo(PushSender sender, long userId) {
        verify(sender, after(SETTLE_MS).never()).send(eq(List.of(userId)), any());
    }

    /** 아무에게도 푸시가 나가지 않는다. */
    public static void noPush(PushSender sender) {
        verify(sender, after(SETTLE_MS).never()).send(any(), any());
    }
}
