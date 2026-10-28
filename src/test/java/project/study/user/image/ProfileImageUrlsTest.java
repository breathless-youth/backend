package project.study.user.image;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ProfileImageUrlsTest {

    @Test
    void base_url이_비어_있으면_상대_경로다() {
        assertThat(new ProfileImageUrls("").of("avatars/12/a.jpg")).isEqualTo("/avatars/12/a.jpg");
    }

    @Test
    void base_url_끝_슬래시는_한_번만_붙는다() {
        assertThat(new ProfileImageUrls("https://web.focusmakers.app/").of("avatars/12/a.jpg"))
                .isEqualTo("https://web.focusmakers.app/avatars/12/a.jpg");
    }

    @Test
    void 키가_없으면_null이다() {
        assertThat(new ProfileImageUrls("").of(null)).isNull();
    }
}
