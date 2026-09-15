package project.study.rtcstats.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

/**
 * 프론트 getStats() 샘플 1건 (BY-490).
 *
 * @param connectionId 프론트가 PeerConnection당 발급하는 UUID (연결 단위 dedup 키)
 * @param at 클라 시계 epoch millis (nullable)
 */
public record RtcStatRequest(
        @NotBlank @Size(max = 64) String connectionId,
        @NotNull @Positive Long roomId,
        @NotNull @Positive Long userId,
        @Positive Long peerUserId,

        @NotBlank
        @Pattern(regexp = "host|srflx|prflx|relay", message = "candidateType은 host|srflx|prflx|relay 중 하나여야 한다")
        String candidateType,

        @Pattern(regexp = "udp|tcp|tls", message = "relayProtocol은 udp|tcp|tls 중 하나여야 한다")
        String relayProtocol,

        @PositiveOrZero Long bytesReceived,
        @PositiveOrZero Long bytesSent,
        @PositiveOrZero Integer rttMs,
        @NotNull Boolean isFinal,
        Long at) {}
