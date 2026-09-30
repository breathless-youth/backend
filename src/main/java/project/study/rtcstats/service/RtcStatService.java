package project.study.rtcstats.service;

import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import project.study.rtcstats.dto.RtcStatRequest;
import project.study.rtcstats.entity.RtcConnectionStat;
import project.study.rtcstats.repository.RtcStatRepository;

@Service
@RequiredArgsConstructor
public class RtcStatService {

    private final RtcStatRepository repository;

    /** getStats() 샘플 1건을 그대로 적재한다 (fire-and-forget 텔레메트리). */
    @Transactional
    public void record(RtcStatRequest request) {
        Instant clientAt = request.at() == null ? null : Instant.ofEpochMilli(request.at());
        repository.save(RtcConnectionStat.builder()
                .connectionId(request.connectionId())
                .roomId(request.roomId())
                .userId(request.userId())
                .peerUserId(request.peerUserId())
                .candidateType(request.candidateType())
                .relayProtocol(request.relayProtocol())
                .bytesReceived(request.bytesReceived())
                .bytesSent(request.bytesSent())
                .rttMs(request.rttMs())
                .isFinal(request.isFinal())
                .clientAt(clientAt)
                .createdAt(Instant.now())
                .build());
    }
}
