package project.study.metrics.amplitude;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/** 다른 패키지의 테스트가 적재 창 집계를 만들 때 쓴다. addEvent는 패키지 안에서만 보인다. */
public final class AmplitudeTestAggregates {

    private AmplitudeTestAggregates() {}

    /** 회원마다 app_launched 한 번과 os 속성 하나. */
    public static WindowAggregate launched(LocalDate date, Instant seenAt, long... userIds) {
        WindowAggregate aggregate = WindowAggregate.empty();
        for (long userId : userIds) {
            aggregate.addEvent(
                    userId,
                    date,
                    List.of("app_launched"),
                    UserAttributeObservation.observed(seenAt, null, false, "ios"));
        }
        return aggregate;
    }
}
