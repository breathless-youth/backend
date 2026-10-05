package project.study.appconfig.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 운영 설정 한 줄. 운영자가 SQL로 바꾸므로 애플리케이션에서는 읽기 전용이다 (ADR-0027). */
@Entity
@Table(name = "app_config")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AppConfig {

    @Id
    @Column(name = "config_key")
    private String key;

    @Column(name = "config_value", nullable = false)
    private String value;

    private String description;

    private Instant updatedAt;
}
