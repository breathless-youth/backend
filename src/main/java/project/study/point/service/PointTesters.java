package project.study.point.service;

import java.util.List;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import project.study.user.service.MemberGuard;

/**
 * 출시 전에 운영에서 포인트를 시험하는 계정 (BY-1318). 스토어 심사 계정과 설정에 적은 팀 테스트 계정이다. 이 계정들은 포인트 시작일 전에도
 * 포인트를 받고, 운영에서도 샌드박스(시험) 결제를 지급받는다. 샌드박스 결제는 돈이 나가지 않으므로 아무에게나 열지 않는다.
 */
@Component
public class PointTesters {

    private final MemberGuard members;
    private final Set<Long> testers;

    public PointTesters(MemberGuard members, @Value("${app.points.testers:}") List<Long> testers) {
        this.members = members;
        this.testers = Set.copyOf(testers);
    }

    public boolean isTester(long userId) {
        return testers.contains(userId) || members.isReviewAccount(userId);
    }
}
