package project.study.rtcstats;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import project.study.rtcstats.dto.RtcStatRequest;
import project.study.rtcstats.entity.RtcConnectionStat;
import project.study.rtcstats.repository.RtcStatRepository;
import project.study.rtcstats.service.RtcStatService;

@ExtendWith(MockitoExtension.class)
class RtcStatServiceTest {

    @Mock
    private RtcStatRepository repository;

    @InjectMocks
    private RtcStatService service;

    private RtcConnectionStat saved() {
        ArgumentCaptor<RtcConnectionStat> captor = ArgumentCaptor.forClass(RtcConnectionStat.class);
        verify(repository).save(captor.capture());
        return captor.getValue();
    }

    @Test
    void 요청을_엔티티로_매핑해_저장한다() {
        service.record(
                new RtcStatRequest("conn-1", 1L, 2L, 3L, "relay", "udp", 1000L, 500L, 40, true, 1_700_000_000_000L));

        RtcConnectionStat s = saved();
        assertThat(s.getConnectionId()).isEqualTo("conn-1");
        assertThat(s.getRoomId()).isEqualTo(1L);
        assertThat(s.getUserId()).isEqualTo(2L);
        assertThat(s.getPeerUserId()).isEqualTo(3L);
        assertThat(s.getCandidateType()).isEqualTo("relay");
        assertThat(s.getRelayProtocol()).isEqualTo("udp");
        assertThat(s.getBytesReceived()).isEqualTo(1000L);
        assertThat(s.getBytesSent()).isEqualTo(500L);
        assertThat(s.getRttMs()).isEqualTo(40);
        assertThat(s.isFinal()).isTrue();
        assertThat(s.getClientAt()).isEqualTo(Instant.ofEpochMilli(1_700_000_000_000L));
        assertThat(s.getCreatedAt()).isNotNull();
    }

    @Test
    void at이_null이면_clientAt은_null이다() {
        service.record(new RtcStatRequest("conn-2", 1L, 2L, null, "host", null, null, null, null, false, null));

        assertThat(saved().getClientAt()).isNull();
    }
}
