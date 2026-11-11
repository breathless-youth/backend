package project.study.point.service;

import java.time.LocalDate;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import project.study.common.exception.BadRequestException;
import project.study.point.dto.PointBalanceResponse;
import project.study.point.dto.PointHistoryItem;
import project.study.point.dto.PointHistoryResponse;
import project.study.point.dto.PointHistoryType;
import project.study.point.dto.PointReason;
import project.study.point.repository.PointRepository;
import project.study.user.service.MemberGuard;

/** 잔액과 포인트 기록. 잔액을 바꾸는 길은 {@link #earn}과 {@link #spend} 둘뿐이고 둘 다 기록 한 줄을 함께 남긴다. */
@Service
@RequiredArgsConstructor
public class PointService {

    private static final int MAX_PAGE_SIZE = 100;

    private final PointRepository repository;
    private final PointLocks locks;
    private final MemberGuard members;

    @Transactional(readOnly = true)
    public PointBalanceResponse balance(Long userId) {
        members.requireMember(userId);
        return new PointBalanceResponse(repository.balance(userId));
    }

    @Transactional(readOnly = true)
    public PointHistoryResponse history(Long userId, PointHistoryType type, Long cursor, int size, String language) {
        members.requireMember(userId);
        if (size < 1 || size > MAX_PAGE_SIZE) {
            throw new BadRequestException("size는 1 이상 " + MAX_PAGE_SIZE + " 이하여야 합니다");
        }
        List<PointHistoryItem> rows = repository.history(userId, type, cursor, size + 1, language);
        boolean hasNext = rows.size() > size;
        List<PointHistoryItem> items = hasNext ? rows.subList(0, size) : rows;
        return new PointHistoryResponse(items, hasNext ? items.getLast().id() : null);
    }

    /** 포인트 사용자 락을 잡는다. 검사와 차감 사이를 막아야 하는 호출자(상점)가 검사 전에 부른다. */
    public void lock(long userId) {
        locks.lockUser(userId);
    }

    /** 같은 (사유, 참조 키)로 이미 받았으면 아무것도 하지 않고 false. */
    @Transactional
    public boolean earn(long userId, PointReason reason, String refKey, int amount, String label, LocalDate statDate) {
        if (amount <= 0) {
            throw new IllegalArgumentException("받는 금액은 양수여야 한다");
        }
        locks.lockUser(userId);
        if (!repository.insertLedger(userId, amount, reason, refKey, label, statDate)) {
            return false;
        }
        repository.addBalance(userId, amount);
        return true;
    }

    /** 잔액이 모자라면 {@link InsufficientPointsException}. 쓴 뒤의 잔액을 돌려준다. */
    @Transactional
    public int spend(long userId, PointReason reason, String refKey, int amount, String label) {
        if (amount <= 0) {
            throw new IllegalArgumentException("쓰는 금액은 양수여야 한다");
        }
        locks.lockUser(userId);
        if (!repository.subtractBalance(userId, amount)) {
            throw new InsufficientPointsException();
        }
        if (!repository.insertLedger(userId, -amount, reason, refKey, label, null)) {
            throw new IllegalStateException("같은 참조 키로 이미 쓴 기록이 있다: " + reason + " " + refKey);
        }
        return repository.balance(userId);
    }
}
