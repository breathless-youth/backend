package project.study.group.service;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import project.study.group.push.GroupNotifications;
import project.study.group.repository.GroupApplicationRepository;
import project.study.group.repository.GroupApplicationRepository.ExpiredRow;
import project.study.group.repository.GroupRepository;
import project.study.group.repository.GroupRepository.GroupRow;

/**
 * 3일 지난 신청을 만료로 닫는다 (BY-1124). 한 트랜잭션에서 기한 된 행을 조건부 UPDATE로 바꾸고, <b>바꾼 행에만</b> 커밋 뒤 푸시를 보낸다
 * — 작업이 두 번 돌거나 서버가 둘이어도 한 행은 한 번만 알린다.
 *
 * <p>푸시에 쓸 그룹 이름은 바꾼 행의 그룹을 한 번에 읽는다(행마다 쿼리하지 않는다). 푸시 발송은 별도 실행기라 이 트랜잭션을 붙잡지 않는다.
 * 그룹이 이미 사라졌다면 이름이 없어 푸시 없이 조용히 닫는다 — 그룹이 사라질 때 신청은 {@code GROUP_DELETED}로 닫히므로 정상 흐름에서는 없다.
 */
@Service
@RequiredArgsConstructor
public class GroupApplicationExpiry {

    private final GroupApplicationRepository applications;
    private final GroupRepository groups;
    private final GroupNotifications notifications;

    /** 기한이 된({@code expires_at <= now}) 신청을 EXPIRED로 바꾸고 바꾼 건수를 돌려준다. */
    @Transactional
    public int expireDue(Instant now) {
        List<ExpiredRow> expired = applications.expireDue(now);
        if (expired.isEmpty()) {
            return 0;
        }
        Map<Long, GroupRow> byId =
                groups
                        .findLiveByIds(expired.stream()
                                .map(ExpiredRow::groupId)
                                .distinct()
                                .toList())
                        .stream()
                        .collect(Collectors.toMap(GroupRow::id, group -> group));
        for (ExpiredRow row : expired) {
            GroupRow group = byId.get(row.groupId());
            if (group != null) {
                notifications.applicationExpired(row.userId(), group.name());
            }
        }
        return expired.size();
    }
}
