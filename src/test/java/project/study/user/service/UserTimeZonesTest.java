package project.study.user.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.ZoneId;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import project.study.user.repository.UserRepository;

@ExtendWith(MockitoExtension.class)
class UserTimeZonesTest {

    private static final ZoneId NEW_YORK = ZoneId.of("America/New_York");

    @Mock
    private UserRepository userRepository;

    @InjectMocks
    private UserTimeZones userTimeZones;

    @Test
    void IANA_이름은_시간대로_읽는다() {
        assertThat(UserTimeZones.parse("America/New_York")).contains(NEW_YORK);
    }

    @Test
    void 비었거나_읽을_수_없는_값은_시간대가_아니다() {
        assertThat(UserTimeZones.parse(null)).isEmpty();
        assertThat(UserTimeZones.parse("")).isEmpty();
        assertThat(UserTimeZones.parse("  ")).isEmpty();
        assertThat(UserTimeZones.parse("Mars/Base")).isEmpty();
        assertThat(UserTimeZones.parse("not a zone")).isEmpty();
    }

    @Test
    void 저장된_시간대가_없으면_서울이다() {
        assertThat(userTimeZones.zoneOf(1L)).isEqualTo(ZoneId.of("Asia/Seoul"));
    }

    @Test
    void 저장된_시간대를_돌려준다() {
        when(userRepository.findTimeZoneById(1L)).thenReturn(Optional.of("America/New_York"));

        assertThat(userTimeZones.zoneOf(1L)).isEqualTo(NEW_YORK);
    }

    @Test
    void 보낸_시간대가_유효하면_저장하고_쓴다() {
        assertThat(userTimeZones.resolve(1L, "America/New_York")).isEqualTo(NEW_YORK);

        verify(userRepository).updateTimeZone(1L, "America/New_York");
    }

    @Test
    void 보낸_시간대가_잘못되면_저장하지_않고_저장된_시간대를_쓴다() {
        when(userRepository.findTimeZoneById(1L)).thenReturn(Optional.of("Europe/London"));

        assertThat(userTimeZones.resolve(1L, "Not/AZone")).isEqualTo(ZoneId.of("Europe/London"));
        assertThat(userTimeZones.resolve(1L, null)).isEqualTo(ZoneId.of("Europe/London"));

        verify(userRepository, never()).updateTimeZone(anyLong(), anyString());
    }
}
