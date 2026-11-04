package project.study.group.service;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import project.study.group.repository.GroupMemberRepository;
import project.study.group.repository.GroupMemberRepository.MemberRow;
import project.study.group.repository.GroupRepository;
import project.study.group.repository.GroupRepository.GroupRow;
import project.study.user.account.UserDataHandler;

/**
 * 계정 병합·탈퇴 시 그룹 처리 (BY-1126) — 그룹장·멤버·신청·멤버 공부 알림 기록·신고 행을 맡는다. 차단은 {@code BlockUserDataHandler}가 맡는다.
 *
 * <p><b>병합</b>: 옮기는 쪽 행을 남는 쪽으로 바꾼다. 부딪히는 멤버·알림 행은 옮기는 쪽을 지우고, 부딪히는 답을 기다리는 신청은 지우지 않고
 * {@code CANCELED}로 닫는다(신청 신고가 신청 ID를 가리킨다). 그룹 개수 한도(5·3·2)는 보지 않는다 — 한도는 새로 하는 행동에만 건다. 그룹장이
 * 바뀐 살아 있는 그룹의 열린 공부방에는 {@code OWNER_CHANGED}가 가고 푸시는 없다.
 *
 * <p><b>탈퇴</b>: 그룹장인 그룹은 가장 먼저 들어온(들어온 시각, 같으면 멤버 행 ID 순) 다른 멤버에게 넘기고({@code group_owner_transferred}
 * 푸시, {@code OWNER_CHANGED}), 혼자면 나가기처럼 그룹을 지운다. 그 뒤 그 사람의 그룹 행을 모두 지운다. 넘기기·그룹 지우기는 넘기기·나가기
 * API와 같은 {@link GroupOwnership}을 쓴다.
 *
 * <p><b>락 순서</b>: 그룹 쪽은 그룹 행(①)을 잡은 뒤 users를 가리키는 행을 넣는다(FK가 users 행에 {@code FOR KEY SHARE}). 병합·탈퇴는 users
 * 행을 {@code FOR UPDATE}로 잡으므로 그보다 먼저 {@link #lockBeforeUsers}로 이 사람과 얽힌 그룹 행을 ID 순으로 잡는다. 푸시·공부방 메시지는
 * 모두 커밋 뒤에 나간다.
 */
@Component
@RequiredArgsConstructor
public class GroupUserDataHandler implements UserDataHandler {

    private record OwnerMoved(long groupId, boolean live) {}

    private final JdbcClient jdbc;
    private final GroupRepository groups;
    private final GroupMemberRepository members;
    private final GroupOwnership ownership;
    private final GroupRoomService groupRooms;
    private final Clock clock;

    @Override
    public Set<String> columns() {
        return Set.of(
                "study_group.owner_id",
                "study_group_member.user_id",
                "study_group_application.user_id",
                "group_study_alert.receiver_id",
                "user_report.reporter_id",
                "user_report.target_user_id");
    }

    @Override
    public int order() {
        return 250;
    }

    /**
     * 이 사람이 살아 있는 멤버이거나 그룹장이거나 {@code PENDING} 신청을 낸 살아 있는 그룹을 ID 순으로 잠근다. 대상을 고르는 읽기는 잠그지 않으므로,
     * 이 문장과 users 행 잠금 사이에 새로 들어간 그룹은 여기서 잡히지 않는다. 그래도 병합 SQL은 그룹 목록이 아니라 조건으로 행을 고르므로 그 그룹의
     * 행도 처리하고, 탈퇴는 그룹마다 다시 잠그며 읽는다(그때는 users 행 뒤라 순서가 거꾸로다 — 드문 경쟁이라 교착이면 DB가 한쪽을 실패시키고 다시
     * 시도하지 않는다).
     */
    @Override
    public void lockBeforeUsers(long userId) {
        groups.lockLiveInvolving(userId);
    }

    @Override
    public void merge(long sourceUserId, long targetUserId) {
        Instant now = clock.instant();
        moveOwnership(sourceUserId, targetUserId, now);
        moveMemberships(sourceUserId, targetUserId);
        moveApplications(sourceUserId, targetUserId, now);
        moveAlerts(sourceUserId, targetUserId);
        moveReports(sourceUserId, targetUserId);
    }

    @Override
    public void erase(long userId) {
        Instant now = clock.instant();
        for (GroupRow group : groups.lockLiveOfMemberOrOwner(userId)) {
            leaveForErase(group, userId, now);
        }
        // 살아 있는 그룹장 그룹은 위에서 모두 넘기거나 지웠다 — 남은 것은 지운 그룹뿐이라 그룹장을 비운다(살아 있는 그룹이면 V43 CHECK가 막는다)
        jdbc.sql("UPDATE study_group SET owner_id = NULL, updated_at = :now WHERE owner_id = :id")
                .param("now", Timestamp.from(now))
                .param("id", userId)
                .update();
        // 신청 신고는 신청 행을 가리킨다 — 지울 신청을 가리키는 참조를 먼저 비운다
        jdbc.sql("""
                        UPDATE user_report SET application_id = NULL
                         WHERE application_id IN (SELECT id FROM study_group_application WHERE user_id = :id)""").param("id", userId).update();
        delete("DELETE FROM user_report WHERE reporter_id = :id OR target_user_id = :id", userId);
        delete("DELETE FROM study_group_application WHERE user_id = :id", userId);
        delete("DELETE FROM study_group_member WHERE user_id = :id", userId);
        delete("DELETE FROM group_study_alert WHERE receiver_id = :id", userId);
    }

    /** 탈퇴하는 사람이 그룹에서 빠진다. 그룹장이면 먼저 넘기거나 그룹을 지우고, 공부방 자리가 남아 있으면 지운다(보통은 탈퇴 전에 방을 나갔다). */
    private void leaveForErase(GroupRow lockedGroup, long userId, Instant now) {
        if (lockedGroup.ownerId() == userId) {
            Optional<Long> successor = firstOtherMember(lockedGroup.id(), userId);
            if (successor.isPresent()) {
                ownership.handOver(lockedGroup, successor.get(), now);
            } else {
                ownership.closeAbandoned(lockedGroup, now);
            }
        }
        groupRooms.memberRemoved(lockedGroup.id(), userId, false);
    }

    /** 가장 먼저 들어온 다른 살아 있는 멤버 — {@link GroupMemberRepository#findLiveByGroups}가 들어온 시각, 멤버 행 ID 순으로 준다. */
    private Optional<Long> firstOtherMember(long groupId, long userId) {
        return members.findLiveByGroups(List.of(groupId)).stream()
                .map(MemberRow::userId)
                .filter(memberId -> memberId != userId)
                .findFirst();
    }

    /** 지운 그룹까지 그룹장을 옮긴다. 살아 있는 그룹의 열린 공부방에만 커밋 뒤 {@code OWNER_CHANGED} — 사람이 같아 푸시는 보내지 않는다. */
    private void moveOwnership(long source, long target, Instant now) {
        List<OwnerMoved> moved = jdbc.sql("""
                        UPDATE study_group SET owner_id = :target, updated_at = :now
                         WHERE owner_id = :source
                        RETURNING id, deleted_at IS NULL AS live""")
                .param("target", target)
                .param("now", Timestamp.from(now))
                .param("source", source)
                .query((rs, n) -> new OwnerMoved(rs.getLong("id"), rs.getBoolean("live")))
                .list();
        moved.stream()
                .filter(OwnerMoved::live)
                .map(OwnerMoved::groupId)
                .sorted()
                .forEach(groupId -> groupRooms.ownerChanged(groupId, target));
    }

    /** 같은 그룹에 둘 다 살아 있는 멤버면 옮기는 쪽 행을 지운다. 나머지(나간 이력 포함)는 옮긴다. */
    private void moveMemberships(long source, long target) {
        move("""
                DELETE FROM study_group_member s
                 WHERE s.user_id = :source AND s.left_at IS NULL
                   AND EXISTS (SELECT 1 FROM study_group_member t
                                WHERE t.group_id = s.group_id AND t.user_id = :target AND t.left_at IS NULL)""", source, target);
        move("UPDATE study_group_member SET user_id = :target WHERE user_id = :source", source, target);
    }

    /**
     * 신청을 옮긴다. 같은 그룹에 둘 다 {@code PENDING}이면 옮기는 쪽을 먼저 {@code CANCELED}로 닫는다(부분 unique {@code sga_pending_uidx}). 옮긴
     * 뒤 남는 쪽이 이미 멤버인 그룹의 {@code PENDING}도 닫는다. 닫을 때는 답하기와 같이 {@code answered_at}을 채운다.
     */
    private void moveApplications(long source, long target, Instant now) {
        update("""
                UPDATE study_group_application s SET status = 'CANCELED', answered_at = :now
                 WHERE s.user_id = :source AND s.status = 'PENDING'
                   AND EXISTS (SELECT 1 FROM study_group_application t
                                WHERE t.group_id = s.group_id AND t.user_id = :target AND t.status = 'PENDING')""", source, target, now);
        move("UPDATE study_group_application SET user_id = :target WHERE user_id = :source", source, target);
        update("""
                UPDATE study_group_application a SET status = 'CANCELED', answered_at = :now
                 WHERE a.user_id = :target AND a.status = 'PENDING'
                   AND EXISTS (SELECT 1 FROM study_group_member m
                                WHERE m.group_id = a.group_id AND m.user_id = :target AND m.left_at IS NULL)""", source, target, now);
    }

    /** 같은 그룹의 알림 기록이 남는 쪽에 있으면 옮기는 쪽 것을 지우고 나머지는 옮긴다. */
    private void moveAlerts(long source, long target) {
        move("""
                DELETE FROM group_study_alert s
                 WHERE s.receiver_id = :source
                   AND EXISTS (SELECT 1 FROM group_study_alert t
                                WHERE t.receiver_id = :target AND t.group_id = s.group_id)""", source, target);
        move("UPDATE group_study_alert SET receiver_id = :target WHERE receiver_id = :source", source, target);
    }

    /** 신고를 옮긴다. 두 계정 사이의 신고는 합치면 자기 자신 신고가 되어 지운다. */
    private void moveReports(long source, long target) {
        move("UPDATE user_report SET reporter_id = :target WHERE reporter_id = :source", source, target);
        move("UPDATE user_report SET target_user_id = :target WHERE target_user_id = :source", source, target);
        move("DELETE FROM user_report WHERE reporter_id = :target AND target_user_id = :target", source, target);
    }

    private void move(String sql, long source, long target) {
        jdbc.sql(sql).param("source", source).param("target", target).update();
    }

    private void update(String sql, long source, long target, Instant now) {
        jdbc.sql(sql)
                .param("source", source)
                .param("target", target)
                .param("now", Timestamp.from(now))
                .update();
    }

    private void delete(String sql, long id) {
        jdbc.sql(sql).param("id", id).update();
    }
}
