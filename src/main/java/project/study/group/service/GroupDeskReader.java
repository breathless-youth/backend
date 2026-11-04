package project.study.group.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import project.study.group.DeskStatus;
import project.study.group.dto.GroupMemberDesk;
import project.study.group.repository.GroupDeskQueries;
import project.study.group.repository.GroupDeskQueries.MemberProfile;
import project.study.group.repository.GroupMemberRepository;
import project.study.group.repository.GroupMemberRepository.MemberRow;
import project.study.group.repository.GroupRepository.GroupRow;
import project.study.studysession.dto.MemberStudy;
import project.study.studysession.service.MemberStudySource;
import project.study.user.image.ProfileImageUrls;

/**
 * 멤버 책상을 만든다 (BY-1124) — 내 그룹 카드·그룹 홈·공개 그룹 보기가 함께 쓴다. 그룹 수와 상관없이 멤버 행·사람 정보·앱 전체 공부 상태·그룹
 * 공부방 자리를 한 번씩만 읽는다(설계 "성능 기준": 내 그룹은 최대 5그룹 × 6명). 같은 사람이 여러 그룹에 있어도 공부 상태는 한 번 읽어 같은 값을 쓴다.
 *
 * <p>공부 상태는 그룹 공부방 자리가 있을 때만 {@code IN_ROOM}이고, 나머지는 앱 전체 상태 그대로다 — 다른 곳(싱글룸·다른 그룹)이 어디인지는
 * 드러내지 않는다. 멤버 공부 상태는 REPEATABLE READ 스냅샷에서 읽으므로, 호출자가 트랜잭션을 열었다면 그 격리 수준이어야 한다
 * (아니면 {@link MemberStudySource}가 거절한다).
 */
@Component
@RequiredArgsConstructor
public class GroupDeskReader {

    private final GroupMemberRepository members;
    private final GroupDeskQueries deskQueries;
    private final MemberStudySource studySource;
    private final ProfileImageUrls profileImageUrls;

    /** 그룹 ID → 그 그룹의 멤버 책상(들어간 순). 넘긴 그룹마다 값이 있다. */
    public Map<Long, List<GroupMemberDesk>> desks(List<GroupRow> groups, Instant asOf) {
        if (groups.isEmpty()) {
            return Map.of();
        }
        Map<Long, GroupRow> byId = new LinkedHashMap<>();
        groups.forEach(group -> byId.putIfAbsent(group.id(), group));
        Set<Long> groupIds = byId.keySet();

        List<MemberRow> rows = members.findLiveByGroups(groupIds);
        Set<Long> userIds = new LinkedHashSet<>();
        rows.forEach(row -> userIds.add(row.userId()));
        Map<Long, MemberProfile> profiles = deskQueries.profiles(userIds);
        Map<Long, MemberStudy> studies = studySource.of(userIds, asOf);
        Map<Long, Set<Long>> roomSeats = deskQueries.confirmedRoomSeats(groupIds);

        Map<Long, List<GroupMemberDesk>> desks = new LinkedHashMap<>();
        groupIds.forEach(groupId -> desks.put(groupId, new ArrayList<>()));
        // 행은 그룹 안에서 들어간 순(joined_at, id)이다
        for (MemberRow row : rows) {
            boolean inRoom = roomSeats.getOrDefault(row.groupId(), Set.of()).contains(row.userId());
            desks.get(row.groupId())
                    .add(desk(
                            row.userId(),
                            byId.get(row.groupId()).ownerId() == row.userId(),
                            profiles.get(row.userId()),
                            studies.get(row.userId()),
                            inRoom));
        }
        desks.replaceAll((groupId, list) -> List.copyOf(list));
        return desks;
    }

    /** 지금 공부 중인 멤버 수 — 이 그룹 공부방(IN_ROOM) + 다른 곳에서 측정 중(STUDYING). 일시정지는 세지 않는다. */
    public static int studyingCount(List<GroupMemberDesk> desks) {
        return (int) desks.stream()
                .filter(desk -> desk.status() == DeskStatus.IN_ROOM || desk.status() == DeskStatus.STUDYING)
                .count();
    }

    private GroupMemberDesk desk(long userId, boolean owner, MemberProfile profile, MemberStudy study, boolean inRoom) {
        return new GroupMemberDesk(
                userId,
                profile == null ? null : profile.nickname(),
                profile == null ? null : profileImageUrls.of(profile.profileImage()),
                owner,
                inRoom ? DeskStatus.IN_ROOM : status(study.presence()),
                study.accumulating(),
                study.todayFocusSec(),
                study.weekFocusSec(),
                study.lastStudiedAt());
    }

    private static DeskStatus status(MemberStudy.Presence presence) {
        return switch (presence) {
            case IDLE -> DeskStatus.IDLE;
            case STUDYING -> DeskStatus.STUDYING;
            case PAUSED -> DeskStatus.PAUSED;
        };
    }
}
