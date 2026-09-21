package project.study.user.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import project.study.common.exception.BadRequestException;
import project.study.user.dto.DdayRequest;
import project.study.user.dto.DdayResponse;
import project.study.user.entity.UserDday;
import project.study.user.repository.UserDdayRepository;

/** 오늘 경계(Asia/Seoul)와 upsert 분기의 순수 규칙 — 저장 경로는 UserDdayApiTest가 검증한다. */
@ExtendWith(MockitoExtension.class)
class UserDdayServiceTest {

    // 고정 현재 시각: UTC로는 아직 09-22이지만 KST로는 09-23 01:00 — 경계는 KST 날짜를 따라야 한다
    private static final Instant NOW = Instant.parse("2026-09-22T16:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final LocalDate TODAY_KST = LocalDate.of(2026, 9, 23);

    @Mock
    private UserDdayRepository ddayRepository;

    private UserDdayService service;

    @BeforeEach
    void setUp() {
        service = new UserDdayService(ddayRepository, CLOCK);
    }

    @Test
    void 오늘_KST_날짜는_허용하고_그_전날은_거절한다() {
        when(ddayRepository.findByUserId(1L)).thenReturn(Optional.empty());
        when(ddayRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        DdayResponse saved = service.save(1L, new DdayRequest("수능", TODAY_KST));
        assertThat(saved.targetDate()).isEqualTo(TODAY_KST);

        assertThatThrownBy(() -> service.save(1L, new DdayRequest("수능", TODAY_KST.minusDays(1))))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void 제목은_앞뒤_공백을_잘라_저장한다() {
        when(ddayRepository.findByUserId(1L)).thenReturn(Optional.empty());
        when(ddayRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        DdayResponse saved = service.save(1L, new DdayRequest("  2027 수능 ", TODAY_KST.plusYears(1)));

        assertThat(saved.title()).isEqualTo("2027 수능");
    }

    @Test
    void 이미_있으면_새로_만들지_않고_덮어쓴다() {
        UserDday existing = new UserDday(1L, "수능", TODAY_KST.plusDays(10));
        when(ddayRepository.findByUserId(1L)).thenReturn(Optional.of(existing));

        DdayResponse saved = service.save(1L, new DdayRequest("토익", TODAY_KST.plusDays(30)));

        assertThat(saved.title()).isEqualTo("토익");
        assertThat(existing.getTitle()).isEqualTo("토익");
        assertThat(existing.getTargetDate()).isEqualTo(TODAY_KST.plusDays(30));
        verify(ddayRepository, never()).save(any());
    }
}
