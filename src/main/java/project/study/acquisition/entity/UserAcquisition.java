package project.study.acquisition.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * 사용자가 처음 들어온 경로. 사용자당 한 행이고 한 번 저장하면 바꾸지 않는다.
 *
 * <p>쓰기는 UserAcquisitionInsert.insertIfAbsent 한 곳뿐이라 생성자가 없다. created_at은 서버가 저장한 시각, captured_at은
 * 앱이 설치 정보를 읽은 시각이다.
 */
@Entity
@Table(name = "user_acquisition")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class UserAcquisition {

    @Id
    @Column(name = "user_id")
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private InstallPlatform platform;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AcquisitionSource source;

    @Column(name = "install_referrer")
    private String installReferrer;

    @Column(name = "deep_link_ad")
    private String deepLinkAd;

    @Column(name = "utm_source")
    private String utmSource;

    @Column(name = "utm_medium")
    private String utmMedium;

    @Column(name = "utm_campaign")
    private String utmCampaign;

    @Column(name = "invite_code")
    private String inviteCode;

    @Column(name = "meta_campaign_id")
    private String metaCampaignId;

    @Column(name = "meta_campaign_name")
    private String metaCampaignName;

    @Column(name = "meta_adset_id")
    private String metaAdsetId;

    @Column(name = "meta_adset_name")
    private String metaAdsetName;

    @Column(name = "meta_ad_id")
    private String metaAdId;

    @Column(name = "meta_ad_name")
    private String metaAdName;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "meta_payload")
    private String metaPayload;

    @Column(name = "captured_at", nullable = false)
    private Instant capturedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
}
