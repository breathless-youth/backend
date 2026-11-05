package project.study.rtcstats.service;

import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import project.study.rtcstats.dto.RtcStatRequest;
import project.study.rtcstats.entity.RtcConnectionStat;
import project.study.rtcstats.repository.RtcStatRepository;
import project.study.user.service.UserService;

@Service
@RequiredArgsConstructor
public class RtcStatService {

    private final RtcStatRepository repository;
    private final UserService userService;

    /**
     * getStats() 샘플 1건을 그대로 적재한다 (fire-and-forget 텔레메트리). rtc_connection_stat은 users FK가 없어, 탈퇴 뒤 남은 옛 토큰의
     * 샘플이 지워진 사람의 행을 다시 만들지 않게 사용자부터 확인한다(없으면 404).
     */
    @Transactional
    public void record(Long userId, RtcStatRequest request) {
        userService.requireExists(userId);
        Instant clientAt = request.at() == null ? null : Instant.ofEpochMilli(request.at());
        repository.save(RtcConnectionStat.builder()
                .connectionId(request.connectionId())
                .roomId(request.roomId())
                .userId(userId)
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
