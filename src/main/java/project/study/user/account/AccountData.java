package project.study.user.account;

import java.util.Comparator;
import java.util.List;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 모든 {@link UserDataHandler}를 order() 순으로 불러 사용자 데이터를 병합·삭제한다. users 행은 호출한 쪽이 다룬다. */
@Component
public class AccountData {

    private final List<UserDataHandler> handlers;

    public AccountData(List<UserDataHandler> handlers) {
        this.handlers = handlers.stream()
                .sorted(Comparator.comparingInt(UserDataHandler::order))
                .toList();
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void merge(long sourceUserId, long targetUserId) {
        // 같으면 처리기마다 "남는 쪽으로 옮긴 뒤 옮기는 쪽 행 삭제"가 자기 데이터를 지운다
        if (sourceUserId == targetUserId) {
            throw new IllegalArgumentException("자기 자신과는 병합할 수 없습니다: " + sourceUserId);
        }
        for (UserDataHandler handler : handlers) {
            handler.merge(sourceUserId, targetUserId);
        }
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void erase(long userId) {
        for (UserDataHandler handler : handlers) {
            handler.erase(userId);
        }
    }
}
