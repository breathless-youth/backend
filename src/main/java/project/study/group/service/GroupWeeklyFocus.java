package project.study.group.service;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import project.study.group.repository.GroupMemberRepository;
import project.study.group.repository.GroupMemberRepository.PublicMemberRef;
import project.study.ranking.engine.StandingsCache;
import project.study.studysession.service.MemberStudySource;

/**
 * 공개 그룹의 이번 주 순공 합계·평균 (BY-1124, 합계 BY-1221) — 합계는 지금 멤버들의 이번 주 순공 합, 평균은 그 합 ÷ 지금 멤버 수(정수 나눗셈,
 * 소수는 버린다). 주 중간에 들어온 멤버도 들어오기 전을 포함한 이번 주 앱 전체 순공이 모두 들어가고, 나간 멤버는 빠진다.
 *
 * <p>목록이 모든 그룹의 합계로 정렬하므로 공개 그룹 전체를 한 번에 계산해 60초 캐시한다(랭킹의 조회 시 집계와 같은 방식, ADR-0028). 캐시는
 * 태스크 메모리라 태스크가 여럿이면 각자 계산한다. 캐시 키가 이번 주 시작 날짜라, 주가 바뀌면 옛 주의 값은 쓰지 않는다. 같은 키 동시 요청은
 * {@link StandingsCache}가 먼저 온 계산을 기다리게 한다.
 *
 * <p>멤버 순공은 확정 합계와 draft를 한 REPEATABLE READ 스냅샷에서 읽어야 하므로({@link MemberStudySource#weekFocus}) 호출자는
 * REPEATABLE READ 읽기 트랜잭션 안에서 부른다. 캐시를 채우는 쪽이 그 트랜잭션에 합류해 연결을 더 쓰지 않으므로, 채우는 동안 기다리는 요청이
 * 연결을 쥐고 있어도 풀이 막히지 않는다. 쓰기 트랜잭션 안에서는 부를 수 없다.
 */
@Component
@RequiredArgsConstructor
public class GroupWeeklyFocus {

    /** 합계·평균이 갱신되는 주기 — 목록·보기 Swagger 설명의 "약 1분마다 갱신". */
    static final Duration TTL = Duration.ofSeconds(60);

    /** 한 번에 읽는 사람 수 — 공개 그룹이 늘어 IN 목록이 드라이버의 바인드 변수 상한(32767)에 닿지 않게 쪼갠다. */
    private static final int USER_CHUNK = 10_000;

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private final GroupMemberRepository members;
    private final MemberStudySource studySource;
    private final StandingsCache cache;

    /** 한 그룹의 이번 주 순공 — 합계와 평균(초). */
    public record WeekFocus(long totalSec, long averageSec) {

        /** 맵에 없는 그룹의 값 — 둘 다 0이다. */
        public static final WeekFocus NONE = new WeekFocus(0, 0);
    }

    /** 그룹 ID → 이번 주 순공. 공개·살아 있는 그룹 전부이고, 멤버가 하나도 없는 그룹은 없다(값이 없으면 {@link WeekFocus#NONE}으로 본다). */
    public Map<Long, WeekFocus> byGroup(Instant now) {
        return cache.get(new WeekKey(weekStart(now)), TTL, () -> compute(now));
    }

    private Map<Long, WeekFocus> compute(Instant now) {
        List<PublicMemberRef> refs = members.findLiveOfPublicGroups();
        Set<Long> userIds = new LinkedHashSet<>();
        refs.forEach(ref -> userIds.add(ref.userId()));
        Map<Long, Long> weekFocus = new HashMap<>();
        List<Long> chunk = new ArrayList<>(Math.min(userIds.size(), USER_CHUNK));
        for (Long userId : userIds) {
            chunk.add(userId);
            if (chunk.size() == USER_CHUNK) {
                weekFocus.putAll(studySource.weekFocus(chunk, now));
                chunk = new ArrayList<>(USER_CHUNK);
            }
        }
        if (!chunk.isEmpty()) {
            weekFocus.putAll(studySource.weekFocus(chunk, now));
        }

        Map<Long, long[]> sumAndCount = new HashMap<>();
        for (PublicMemberRef ref : refs) {
            long[] acc = sumAndCount.computeIfAbsent(ref.groupId(), id -> new long[2]);
            acc[0] += weekFocus.getOrDefault(ref.userId(), 0L);
            acc[1]++;
        }
        Map<Long, WeekFocus> byGroup = new HashMap<>();
        sumAndCount.forEach((groupId, acc) -> byGroup.put(groupId, new WeekFocus(acc[0], acc[0] / acc[1])));
        return Map.copyOf(byGroup);
    }

    /** 이번 주 월요일(KST) — 순공 집계의 주 경계({@code MemberStudySource})와 같다. */
    private static LocalDate weekStart(Instant now) {
        return LocalDate.ofInstant(now, KST).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
    }

    private record WeekKey(LocalDate weekStart) {}
}
