package project.study.user.service;

import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.sentry.Sentry;
import io.sentry.SentryLevel;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import project.study.room.service.CurrentRoomLeaver;
import project.study.user.dto.DeleteAccountRequest;
import project.study.user.entity.Provider;
import project.study.user.image.ProfileImageStorage;
import project.study.user.oauth.AppleTokenRevoker;

/** 탈퇴의 커밋 뒤 정리 — 애플 해지를 못 하는 경우가 로그에만 묻히지 않고 Sentry에 남는지. */
@ExtendWith(MockitoExtension.class)
class AccountDeletionServiceTest {

    @Mock
    private CurrentRoomLeaver currentRoomLeaver;

    @Mock
    private AccountEraser accountEraser;

    @Mock
    private ProfileImageStorage profileImageStorage;

    @Mock
    private AppleTokenRevoker appleTokenRevoker;

    @InjectMocks
    private AccountDeletionService service;

    private final MockedStatic<Sentry> sentry = mockStatic(Sentry.class);

    @AfterEach
    void closeSentry() {
        sentry.close();
    }

    @Test
    void 애플_계정이_인가_코드_없이_탈퇴하면_해지는_못_하고_Sentry에_남긴다() {
        when(accountEraser.erase(7L)).thenReturn(Optional.of(new AccountEraser.ErasedAccount(Provider.APPLE, null)));

        service.delete(7L, new DeleteAccountRequest(null, null));

        verifyNoInteractions(appleTokenRevoker);
        sentry.verify(() ->
                Sentry.captureMessage(argThat(message -> message.startsWith("애플 토큰 해지")), eq(SentryLevel.WARNING)));
    }

    @Test
    void 애플이_아닌_계정은_Sentry에_남기지_않는다() {
        when(accountEraser.erase(7L)).thenReturn(Optional.of(new AccountEraser.ErasedAccount(Provider.GOOGLE, null)));

        service.delete(7L, null);

        sentry.verifyNoInteractions();
    }
}
