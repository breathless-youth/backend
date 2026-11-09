package project.study.group.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import project.study.group.repository.GroupMemberRepository;
import project.study.group.repository.GroupMemberRepository.PublicMemberRef;
import project.study.group.service.GroupWeeklyFocus.WeekFocus;
import project.study.ranking.engine.StandingsCache;
import project.study.room.support.MutableClock;
import project.study.studysession.service.MemberStudySource;

/** 공개 그룹 이번 주 순공 (BY-1124, 합계 BY-1221) — 합계와 합 ÷ 멤버 수(버림), 60초 캐시, 주가 바뀌면 버림, 많은 사람은 쪼개 읽기. DB 없이 본다. */
class GroupWeeklyFocusTest {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    /** 2026-10-14(수) 15:00 KST — 이번 주는 10/12(월)부터다. */
    private static final Instant WEDNESDAY =
            ZonedDateTime.of(2026, 10, 14, 15, 0, 0, 0, KST).toInstant();

    private final MutableClock clock = MutableClock.at(WEDNESDAY);
    private GroupMemberRepository members;
    private MemberStudySource studySource;
    private GroupWeeklyFocus focus;

    @BeforeEach
    void setUp() {
        members = mock(GroupMemberRepository.class);
        studySource = mock(MemberStudySource.class);
        focus = new GroupWeeklyFocus(members, studySource, new StandingsCache(clock));
    }

    private void membership(PublicMemberRef... refs) {
        when(members.findLiveOfPublicGroups()).thenReturn(List.of(refs));
    }

    private void weekFocus(Map<Long, Long> weekFocus) {
        when(studySource.weekFocus(anyCollection(), any())).thenReturn(weekFocus);
    }

    @Test
    void 합계는_멤버들의_이번_주_순공_합이고_평균은_그_합을_멤버_수로_나눈_값이며_소수는_버린다() {
        membership(
                new PublicMemberRef(1, 10),
                new PublicMemberRef(1, 11),
                new PublicMemberRef(1, 12),
                new PublicMemberRef(2, 20));
        weekFocus(Map.of(10L, 700L, 11L, 300L, 12L, 0L, 20L, 59L));

        Map<Long, WeekFocus> result = focus.byGroup(WEDNESDAY);

        assertThat(result)
                .containsEntry(1L, new WeekFocus(1000, 333))
                .containsEntry(2L, new WeekFocus(59, 59))
                .hasSize(2);
    }

    @Test
    void 한_사람이_여러_그룹에_있어도_순공은_한_번만_읽고_각_그룹에_더한다() {
        membership(new PublicMemberRef(1, 10), new PublicMemberRef(2, 10), new PublicMemberRef(2, 11));
        weekFocus(Map.of(10L, 600L, 11L, 0L));

        Map<Long, WeekFocus> result = focus.byGroup(WEDNESDAY);

        assertThat(result).containsEntry(1L, new WeekFocus(600, 600)).containsEntry(2L, new WeekFocus(600, 300));
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<Long>> ids = ArgumentCaptor.forClass(Collection.class);
        verify(studySource).weekFocus(ids.capture(), any());
        assertThat(ids.getValue()).containsExactlyInAnyOrder(10L, 11L);
    }

    @Test
    void 공개_그룹이_없으면_빈_맵이고_순공을_읽지_않는다() {
        membership();

        assertThat(focus.byGroup(WEDNESDAY)).isEmpty();
        verify(studySource, never()).weekFocus(anyCollection(), any());
    }

    @Test
    void 요청_시각까지를_순공_기준_시각으로_넘긴다() {
        membership(new PublicMemberRef(1, 10));
        weekFocus(Map.of(10L, 100L));

        focus.byGroup(WEDNESDAY);

        verify(studySource).weekFocus(anyCollection(), eq(WEDNESDAY));
    }

    @Test
    void 캐시_수명_60초_안에서는_다시_계산하지_않고_지나면_다시_계산한다() {
        membership(new PublicMemberRef(1, 10));
        weekFocus(Map.of(10L, 100L));

        focus.byGroup(WEDNESDAY);
        clock.advance(Duration.ofSeconds(59));
        focus.byGroup(WEDNESDAY.plusSeconds(59));
        verify(members, times(1)).findLiveOfPublicGroups();

        clock.advance(Duration.ofSeconds(1));
        focus.byGroup(WEDNESDAY.plusSeconds(60));
        verify(members, times(2)).findLiveOfPublicGroups();
    }

    @Test
    void 주가_바뀌면_60초가_안_지났어도_캐시를_버린다() {
        // 일요일 23:59:30 KST에 계산하고, 30초 뒤 월요일 00:00:00 KST에 다시 부른다
        Instant sunday = ZonedDateTime.of(2026, 10, 18, 23, 59, 30, 0, KST).toInstant();
        clock.set(sunday);
        membership(new PublicMemberRef(1, 10));
        weekFocus(Map.of(10L, 100L));

        focus.byGroup(sunday);
        clock.advance(Duration.ofSeconds(30));
        focus.byGroup(sunday.plusSeconds(30));

        verify(members, times(2)).findLiveOfPublicGroups();
        verify(studySource).weekFocus(anyCollection(), eq(sunday));
        verify(studySource).weekFocus(anyCollection(), eq(sunday.plusSeconds(30)));
    }

    @Test
    void 사람이_많으면_쪼개서_읽는다() {
        List<PublicMemberRef> refs = new ArrayList<>();
        Map<Long, Long> weekFocus = new HashMap<>();
        for (long userId = 1; userId <= 25_000; userId++) {
            refs.add(new PublicMemberRef(userId, userId)); // 한 사람 그룹 25000개
            weekFocus.put(userId, 60L);
        }
        when(members.findLiveOfPublicGroups()).thenReturn(refs);
        when(studySource.weekFocus(anyCollection(), any())).thenReturn(weekFocus);

        Map<Long, WeekFocus> result = focus.byGroup(WEDNESDAY);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<Long>> ids = ArgumentCaptor.forClass(Collection.class);
        verify(studySource, times(3)).weekFocus(ids.capture(), any());
        assertThat(ids.getAllValues()).extracting(Collection::size).containsExactly(10_000, 10_000, 5_000);
        assertThat(result).hasSize(25_000).containsEntry(25_000L, new WeekFocus(60, 60));
    }
}
