package project.study.room.support;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/** 테스트가 시각을 밀 수 있는 Clock. */
public class MutableClock extends Clock {

    private Instant now;

    private MutableClock(Instant now) {
        this.now = now;
    }

    public static MutableClock at(Instant now) {
        return new MutableClock(now);
    }

    public void advance(Duration duration) {
        now = now.plus(duration);
    }

    public void set(Instant instant) {
        now = instant;
    }

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return this;
    }

    @Override
    public Instant instant() {
        return now;
    }
}
