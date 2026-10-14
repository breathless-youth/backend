package project.study.ranking.engine;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 랭킹 순위표 메모리 캐시 (BY-828, ADR-0028) — 키마다 수명이 있고, 같은 키 동시 요청은 먼저 온 계산을 기다렸다 그 결과를 쓴다.
 * 태스크 메모리라 태스크가 여럿이면 각자 계산한다.
 */
@Component
public class StandingsCache {

    private final Clock clock;
    private final ConcurrentHashMap<Object, Slot> slots = new ConcurrentHashMap<>();

    public StandingsCache(Clock clock) {
        this.clock = clock;
    }

    @SuppressWarnings("unchecked")
    public <T> T get(Object key, Duration ttl, Supplier<T> loader) {
        Slot slot = slots.computeIfAbsent(key, k -> new Slot());
        synchronized (slot) {
            Instant now = clock.instant();
            if (slot.value == null || !now.isBefore(slot.expiresAt)) {
                slot.value = loader.get();
                slot.expiresAt = now.plus(ttl);
            }
            return (T) slot.value;
        }
    }

    /** 만료된 지 10분 넘은 키를 지운다 — 지난 기간 키가 쌓이지 않게. */
    @Scheduled(fixedDelay = 600_000)
    public void evictExpired() {
        Instant cutoff = clock.instant().minus(Duration.ofMinutes(10));
        slots.entrySet().removeIf(entry -> entry.getValue().expiresAt.isBefore(cutoff));
    }

    /** 시계를 고정한 테스트가 서로의 캐시를 보지 않게 비운다. */
    public void clear() {
        slots.clear();
    }

    private static final class Slot {

        private Object value;
        private volatile Instant expiresAt = Instant.MIN;
    }
}
