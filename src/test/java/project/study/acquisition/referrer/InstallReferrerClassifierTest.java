package project.study.acquisition.referrer;

import static org.assertj.core.api.Assertions.assertThat;
import static project.study.acquisition.MetaReferrerFixture.CIPHER;
import static project.study.acquisition.MetaReferrerFixture.KEY;
import static project.study.acquisition.MetaReferrerFixture.NONCE;
import static project.study.acquisition.MetaReferrerFixture.PLAIN;
import static project.study.acquisition.MetaReferrerFixture.encrypt;
import static project.study.acquisition.MetaReferrerFixture.metaReferrer;
import static project.study.acquisition.entity.AcquisitionSource.INVITE;
import static project.study.acquisition.entity.AcquisitionSource.META_AD;
import static project.study.acquisition.entity.AcquisitionSource.ORGANIC;
import static project.study.acquisition.entity.AcquisitionSource.OTHER_LINK;
import static project.study.acquisition.entity.AcquisitionSource.UNKNOWN;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

@ExtendWith(OutputCaptureExtension.class)
class InstallReferrerClassifierTest {

    private final InstallReferrerClassifier classifier = new InstallReferrerClassifier(new MetaReferrerDecryptor(KEY));

    @Test
    void 메타_암호문이면_풀어서_계층을_맞춰_꺼낸다() {
        Attribution a = classifier.classify(metaReferrer(CIPHER, NONCE), "aos_v3");
        assertThat(a.source()).isEqualTo(META_AD);
        assertThat(a.metaAd())
                .isEqualTo(new MetaAdDetail(
                        "654321",
                        "Adams Test Campaign Group",
                        "987654",
                        "Adams Test Campaign",
                        "54321",
                        "Adams Test Ad Group",
                        PLAIN));
        assertThat(a.utm()).isEqualTo(new Utm("apps.facebook.com", null, "fb4a"));
    }

    @Test
    void utm_source가_없어도_암호문이_있으면_메타다() { // 메타 문서: utm_source는 보장되지 않는다
        String referrer = metaReferrer(CIPHER, NONCE).replace("utm_source=apps.facebook.com&", "");
        assertThat(classifier.classify(referrer, null).source()).isEqualTo(META_AD);
    }

    @Test
    void 암호문을_못_풀어도_메타이고_상세는_없다() {
        Attribution a = new InstallReferrerClassifier(new MetaReferrerDecryptor(""))
                .classify(metaReferrer(CIPHER, NONCE), null);
        assertThat(a.source()).isEqualTo(META_AD);
        assertThat(a.metaAd()).isNull();
    }

    @Test
    void 평문이_JSON_객체가_아니면_메타이고_상세는_없고_WARN을_남긴다(CapturedOutput output) {
        Attribution a = classifier.classify(metaReferrer(encrypt("[1]"), NONCE), null);
        assertThat(a.source()).isEqualTo(META_AD);
        assertThat(a.metaAd()).isNull();
        assertThat(output).contains("메타 설치 정보 평문이 JSON 객체가 아니다");
    }

    @Test
    void 메타_암호문이_유효한_초대코드보다_먼저다() {
        Attribution a = classifier.classify(metaReferrer(CIPHER, NONCE) + "&code=1234", null);
        assertThat(a.source()).isEqualTo(META_AD);
        assertThat(a.inviteCode()).isNull();
    }

    @Test
    void 숫자_ID도_문자열로_꺼낸다() {
        String cipher = encrypt(
                "{\"campaign_group_id\":120200000000000001,\"campaign_id\":120200000000000002,\"adgroup_id\":120200000000000003}");
        MetaAdDetail m = classifier.classify(metaReferrer(cipher, NONCE), null).metaAd();
        assertThat(m.campaignId()).isEqualTo("120200000000000001");
        assertThat(m.adsetId()).isEqualTo("120200000000000002");
        assertThat(m.adId()).isEqualTo("120200000000000003");
        assertThat(m.campaignName()).isNull();
    }

    @Test
    void 초대코드_4자리면_친구초대() {
        Attribution a = classifier.classify("code=0123", null);
        assertThat(a.source()).isEqualTo(INVITE);
        assertThat(a.inviteCode()).isEqualTo("0123");
    }

    @ParameterizedTest
    @ValueSource(strings = {"code=123", "code=12345", "code=abcd"})
    void 초대코드_형식이_아니면_그밖의링크(String referrer) {
        assertThat(classifier.classify(referrer, null).source()).isEqualTo(OTHER_LINK);
    }

    @Test
    void 초대가_딥링크보다_먼저다() {
        assertThat(classifier.classify("code=1234", "aos_v1").source()).isEqualTo(INVITE);
    }

    @Test
    void 딥링크만_있으면_메타() {
        Attribution a = classifier.classify(null, "ios_v2");
        assertThat(a.source()).isEqualTo(META_AD);
        assertThat(a.metaAd()).isNull();
    }

    @Test
    void 딥링크가_오가닉보다_먼저다() {
        assertThat(classifier
                        .classify("utm_source=google-play&utm_medium=organic", "aos_v1")
                        .source())
                .isEqualTo(META_AD);
    }

    @Test
    void 플레이_오가닉() {
        Attribution a = classifier.classify("utm_source=google-play&utm_medium=organic", null);
        assertThat(a.source()).isEqualTo(ORGANIC);
        assertThat(a.utm()).isEqualTo(new Utm("google-play", "organic", null));
    }

    @Test
    void 그밖의_UTM은_그밖의링크이고_값을_남긴다() {
        Attribution a = classifier.classify("utm_source=insta_bio&utm_medium=social&utm_campaign=launch%20week", null);
        assertThat(a.source()).isEqualTo(OTHER_LINK);
        assertThat(a.utm()).isEqualTo(new Utm("insta_bio", "social", "launch week"));
    }

    @Test
    void not_set도_그밖의링크() {
        assertThat(classifier
                        .classify("utm_source=(not%20set)&utm_medium=(not%20set)", null)
                        .source())
                .isEqualTo(OTHER_LINK);
    }

    @Test
    void 깨진_퍼센트_인코딩도_예외없이_그밖의링크() {
        assertThat(classifier.classify("utm_source=a%2&x=%E0%A4%A", null).source())
                .isEqualTo(OTHER_LINK);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "utm_content=hello",
                "utm_content=%5B1%2C2%5D",
                "utm_content=%7B%22source%22%3A%7B%22data%22%3A%22ab%22%7D%7D"
            })
    void utm_content가_JSON이_아니거나_data_nonce가_없으면_메타가_아니다(String referrer) {
        assertThat(classifier.classify(referrer, null).source()).isEqualTo(OTHER_LINK);
    }

    @Test
    void 아무것도_없으면_알수없음() {
        Attribution a = classifier.classify(null, null);
        assertThat(a.source()).isEqualTo(UNKNOWN);
        assertThat(a.utm()).isEqualTo(new Utm(null, null, null));
    }
}
