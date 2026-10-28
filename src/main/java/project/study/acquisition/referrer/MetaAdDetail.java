package project.study.acquisition.referrer;

/**
 * 복호화한 메타 광고 정보. 메타 필드명은 광고 계층과 한 단계씩 어긋나 있어(campaign_group = 캠페인, campaign = 광고 세트, adgroup = 광고)
 * 아래 이름은 메타 광고 관리자 기준으로 맞춘 것이다. payload는 복호화한 평문 JSON 원문.
 */
public record MetaAdDetail(
        String campaignId,
        String campaignName,
        String adsetId,
        String adsetName,
        String adId,
        String adName,
        String payload) {}
