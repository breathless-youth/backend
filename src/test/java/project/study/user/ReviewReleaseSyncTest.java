package project.study.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willReturn;

import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import project.study.TestcontainersConfiguration;
import project.study.user.service.AppStoreVersionSource;
import project.study.user.service.ReviewReleaseSyncService;

/** 심사용 로그인의 출시 버전을 App Store에 맞춘다 (BY-1312). 스토어 조회는 가짜로 바꾼다. */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class ReviewReleaseSyncTest {

    @Autowired
    ReviewReleaseSyncService service;

    @Autowired
    JdbcClient jdbc;

    @MockitoBean
    AppStoreVersionSource store;

    @BeforeEach
    void setUp() {
        jdbc.sql("DELETE FROM review_login").update();
        jdbc.sql("""
                INSERT INTO review_login (id, login_id, password_hash, released_version)
                VALUES (1, 'review', 'x', '26.46.0')""").update();
    }

    @Test
    void 스토어_버전이_더_높으면_올린다() {
        given(store.releasedVersion()).willReturn(Optional.of("26.47.0"));

        assertThat(service.sync()).isTrue();

        assertThat(released()).isEqualTo("26.47.0");
    }

    @Test
    void 스토어_버전이_같거나_낮으면_그대로_둔다() {
        given(store.releasedVersion()).willReturn(Optional.of("26.46.0"));
        assertThat(service.sync()).isFalse();
        given(store.releasedVersion()).willReturn(Optional.of("26.45.9"));
        assertThat(service.sync()).isFalse();
        // 형식이 어긋난 값은 높은 버전으로 보지 않는다
        given(store.releasedVersion()).willReturn(Optional.of("99.0.0."));
        assertThat(service.sync()).isFalse();
        given(store.releasedVersion()).willReturn(Optional.of("99.0.0-beta"));
        assertThat(service.sync()).isFalse();

        assertThat(released()).isEqualTo("26.46.0");
    }

    @Test
    void 조회가_실패하거나_꺼져_있으면_건드리지_않는다() {
        given(store.releasedVersion()).willThrow(new IllegalStateException("timeout"));
        assertThat(service.sync()).isFalse();
        assertThat(released()).isEqualTo("26.46.0");

        jdbc.sql("UPDATE review_login SET released_version = NULL").update();
        willReturn(Optional.of("99.0.0")).given(store).releasedVersion();
        assertThat(service.sync()).isFalse();
        assertThat(released()).isNull();
    }

    private String released() {
        return jdbc.sql("SELECT released_version FROM review_login WHERE id = 1")
                .query((rs, n) -> rs.getString(1))
                .list()
                .getFirst();
    }
}
