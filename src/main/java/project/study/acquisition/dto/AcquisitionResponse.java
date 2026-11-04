package project.study.acquisition.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import project.study.acquisition.entity.AcquisitionSource;
import project.study.acquisition.entity.UserAcquisition;
import project.study.acquisition.referrer.GoogleAdLabel;

public record AcquisitionResponse(
        @Schema(description = "유입 경로. META_AD·GOOGLE_AD·INVITE·ORGANIC·OTHER_LINK·UNKNOWN", example = "META_AD")
        AcquisitionSource source,

        @Schema(description = "메타 광고 정보. source가 META_AD일 때만 있다")
        MetaAd metaAd,

        @Schema(description = "구글 광고 정보. source가 GOOGLE_AD일 때만 있다")
        GoogleAd googleAd,

        @Schema(description = "친구 초대 코드 4자리. source가 INVITE일 때만 있다", example = "0123")
        String inviteCode,

        @Schema(description = "처음 저장된 값의 설치 정보 읽은 시각(UTC)", example = "2026-10-29T01:12:00Z")
        Instant firstTouchAt) {

    /** 메타 ID는 모두 문자열이다. 암호문을 풀지 못했거나 iOS면 label만 있고 ID는 null이다. */
    public record MetaAd(
            @Schema(description = "메타 캠페인 ID", example = "654321")
            String campaignId,

            @Schema(description = "메타 광고 세트 ID", example = "987654")
            String adsetId,

            @Schema(description = "메타 광고 ID", example = "54321")
            String adId,

            @Schema(description = "앱이 보낸 딥링크 광고 라벨", example = "aos_v3")
            String label) {}

    /** gclid는 설치 정보 원문에만 두고 여기 싣지 않는다. */
    public record GoogleAd(
            @Schema(
                    description = "앱이 보낸 구글 광고 그룹 라벨. gclid만 있었거나 형식(^g_[a-z0-9_]{1,30}$)이 틀리면 null",
                    example = "g_planner")
            String label) {}

    public static AcquisitionResponse from(UserAcquisition a) {
        MetaAd metaAd = a.getSource() == AcquisitionSource.META_AD
                ? new MetaAd(a.getMetaCampaignId(), a.getMetaAdsetId(), a.getMetaAdId(), a.getDeepLinkAd())
                : null;
        GoogleAd googleAd = a.getSource() == AcquisitionSource.GOOGLE_AD
                ? new GoogleAd(GoogleAdLabel.of(a.getGoogleDeepLinkAd()))
                : null;
        return new AcquisitionResponse(a.getSource(), metaAd, googleAd, a.getInviteCode(), a.getCapturedAt());
    }
}
