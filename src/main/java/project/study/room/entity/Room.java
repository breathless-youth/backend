package project.study.room.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 방 한 개 = 행 하나. 라이브 상태이자 이력이다 (closed_at이 찍히면 닫힌 방, 행은 영구 보존).
 *
 * <p>쓰기·락 경로는 전부 {@code RoomRepository}의 네이티브 SQL이다. 이 엔티티는 {@code ddl-auto: validate}
 * 스키마 검증과 테스트 조회에만 쓴다 — 영속성 컨텍스트가 낡은 값을 돌려주는 문제를 피하기 위해
 * 운영 코드에서 관리 엔티티와 네이티브 갱신을 같은 트랜잭션에서 섞지 않는다 (스펙 §2 원칙 3).
 */
@Table(name = "rooms")
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Room {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 4)
    private String inviteCode;

    @Column(nullable = false, updatable = false)
    private Long createdBy;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    private Instant closedAt;

    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private CloseReason closeReason;
}
