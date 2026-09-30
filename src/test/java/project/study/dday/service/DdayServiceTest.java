package project.study.dday.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import project.study.common.exception.BadRequestException;
import project.study.dday.dto.DdayRequest;
import project.study.dday.dto.DdayResponse;
import project.study.dday.repository.DdayRepository;

/** 오늘 경계(Asia/Seoul)와 제목 정리의 순수 규칙 — 저장·동시성은 DdayApiTest가 실제 DB로 검증한다. */
@ExtendWith(MockitoExtension.class)
class DdayServiceTest {

    // 고정 현재 시각: UTC로는 아직 09-22이지만 KST로는 09-23 01:00 — 경계는 KST 날짜를 따라야 한다
    private static final Instant NOW = Instant.parse("2026-09-22T16:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final LocalDate TODAY_KST = LocalDate.of(2026, 9, 23);

    @Mock
    private DdayRepository ddayRepository;

    private DdayService service;

    @BeforeEach
    void setUp() {
        service = new DdayService(ddayRepository, CLOCK);
    }

    @Test
    void 오늘_KST_날짜는_허용하고_그_전날은_거절한다() {
        DdayResponse saved = service.save(1L, new DdayRequest("수능", TODAY_KST));
        assertThat(saved.targetDate()).isEqualTo(TODAY_KST);
        verify(ddayRepository).upsert(1L, "수능", TODAY_KST);

        assertThatThrownBy(() -> service.save(1L, new DdayRequest("수능", TODAY_KST.minusDays(1))))
                .isInstanceOf(BadRequestException.class);
        verify(ddayRepository, never()).upsert(any(), any(), eq(TODAY_KST.minusDays(1)));
    }

    @Test
    void 제목은_앞뒤_공백을_잘라_저장한다() {
        LocalDate target = TODAY_KST.plusYears(1);

        DdayResponse saved = service.save(1L, new DdayRequest("  2027 수능 ", target));

        assertThat(saved.title()).isEqualTo("2027 수능");
        verify(ddayRepository).upsert(1L, "2027 수능", target);
    }
}
