package project.study.acquisition.repository;

import java.sql.Timestamp;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import project.study.acquisition.referrer.Attribution;
import project.study.acquisition.referrer.MetaAdDetail;
import project.study.acquisition.referrer.Utm;

/** ON CONFLICT DO NOTHING으로 첫 값을 지킨다. 먼저 조회하고 넣으면 동시 요청에서 둘 다 넣으려다 한쪽이 실패한다. */
@RequiredArgsConstructor
public class UserAcquisitionInsertImpl implements UserAcquisitionInsert {

    private static final String INSERT = """
            INSERT INTO user_acquisition (
                user_id, platform, source, install_referrer, deep_link_ad,
                utm_source, utm_medium, utm_campaign, invite_code,
                meta_campaign_id, meta_campaign_name, meta_adset_id, meta_adset_name,
                meta_ad_id, meta_ad_name, meta_payload, captured_at, created_at)
            VALUES (
                :userId, :platform, :source, :installReferrer, :deepLinkAd,
                :utmSource, :utmMedium, :utmCampaign, :inviteCode,
                :metaCampaignId, :metaCampaignName, :metaAdsetId, :metaAdsetName,
                :metaAdId, :metaAdName, cast(:metaPayload as jsonb), :capturedAt, now())
            ON CONFLICT (user_id) DO NOTHING""";

    private final JdbcClient jdbc;

    @Override
    public void insertIfAbsent(NewAcquisition row) {
        Attribution attribution = row.attribution();
        Utm utm = attribution.utm();
        MetaAdDetail meta = attribution.metaAd();
        jdbc.sql(INSERT)
                .param("userId", row.userId())
                .param("platform", row.platform().name())
                .param("source", attribution.source().name())
                .param("installReferrer", row.installReferrer())
                .param("deepLinkAd", row.deepLinkAd())
                .param("utmSource", utm.source())
                .param("utmMedium", utm.medium())
                .param("utmCampaign", utm.campaign())
                .param("inviteCode", attribution.inviteCode())
                .param("metaCampaignId", meta == null ? null : meta.campaignId())
                .param("metaCampaignName", meta == null ? null : meta.campaignName())
                .param("metaAdsetId", meta == null ? null : meta.adsetId())
                .param("metaAdsetName", meta == null ? null : meta.adsetName())
                .param("metaAdId", meta == null ? null : meta.adId())
                .param("metaAdName", meta == null ? null : meta.adName())
                .param("metaPayload", meta == null ? null : meta.payload())
                .param("capturedAt", Timestamp.from(row.capturedAt()))
                .update();
    }
}
