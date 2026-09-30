package project.study.rtcstats.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

/**
 * 구 앱(v1.2.x)의 WebRTC 통계 샘플 — 본문 userId + {@link RtcStatRequest}와 같은 필드. contract 시 삭제 (ADR-0020).
 */
public record LegacyRtcStatRequest(
        @NotBlank @Size(max = 64) String connectionId,
        @NotNull @Positive Long roomId,

        @Schema(description = "보고하는 유저 ID", example = "1") @NotNull @Positive
        Long userId,

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
        Long at) {

    public RtcStatRequest toRequest() {
        return new RtcStatRequest(
                connectionId,
                roomId,
                peerUserId,
                candidateType,
                relayProtocol,
                bytesReceived,
                bytesSent,
                rttMs,
                isFinal,
                at);
    }
}
