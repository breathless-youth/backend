package project.study.point.service;

import java.time.LocalDate;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import project.study.common.exception.BadRequestException;
import project.study.point.dto.PointAccount;
import project.study.point.dto.PointBalanceResponse;
import project.study.point.dto.PointHistoryItem;
import project.study.point.dto.PointHistoryResponse;
import project.study.point.dto.PointHistoryType;
import project.study.point.dto.PointReason;
import project.study.point.repository.PointRepository;
import project.study.user.service.MemberGuard;

/**
 * 잔액과 포인트 기록. 잔액을 바꾸는 길은 {@link #earn}·{@link #charge}·{@link #spend}·{@link #reclaim}뿐이고 모두 기록 한 줄을 함께
 * 남긴다. 잔액은 무료 몫과 결제 몫으로 나뉘고, 쓸 때는 무료 몫부터 빠진다. 환불로만 음수가 될 수 있다.
 */
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
        PointAccount account = repository.account(userId);
        return new PointBalanceResponse(account.balance(), account.paidBalance());
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

    /**
     * 결제로 산 포인트(paid)와 덤(bonus)을 준다. 덤은 무료 몫이다. 같은 참조 키로 이미 받았으면 아무것도 하지 않고 false. 환불로 잔액이
     * 음수였다면 그만큼을 먼저 메우고, 0 위로 남은 만큼만 결제 몫이 된다.
     */
    @Transactional
    public boolean charge(long userId, String refKey, int paid, int bonus, String label) {
        if (paid <= 0 || bonus < 0) {
            throw new IllegalArgumentException("충전 금액은 양수여야 한다");
        }
        locks.lockUser(userId);
        PointAccount account = repository.account(userId);
        int balance = account.balance() + paid + bonus;
        int paidDelta = Math.min(account.paidBalance() + paid, Math.max(balance, 0)) - account.paidBalance();
        if (!repository.insertLedger(userId, paid + bonus, paidDelta, PointReason.CHARGE, refKey, label, null)) {
            return false;
        }
        repository.addBalance(userId, paid + bonus, paidDelta);
        return true;
    }

    /** 잔액이 모자라면 {@link InsufficientPointsException}. 무료 몫부터 쓰고 모자란 만큼만 결제 몫에서 뺀다. 쓴 뒤의 잔액을 돌려준다. */
    @Transactional
    public int spend(long userId, PointReason reason, String refKey, int amount, String label) {
        if (amount <= 0) {
            throw new IllegalArgumentException("쓰는 금액은 양수여야 한다");
        }
        locks.lockUser(userId);
        PointAccount account = repository.account(userId);
        if (account.balance() < amount) {
            throw new InsufficientPointsException();
        }
        int fromPaid = Math.max(0, amount - account.freeBalance());
        repository.setAccount(userId, new PointAccount(account.balance() - amount, account.paidBalance() - fromPaid));
        if (!repository.insertLedger(userId, -amount, -fromPaid, reason, refKey, label, null)) {
            throw new IllegalStateException("같은 참조 키로 이미 쓴 기록이 있다: " + reason + " " + refKey);
        }
        return account.balance() - amount;
    }

    /**
     * 환불된 결제의 포인트(paid + bonus)를 전부 되돌린다. 이미 써서 잔액이 모자라면 잔액이 음수가 된다. 되돌린 뒤의 잔액을 돌려준다. 결제
     * 몫은 그 결제로 산 만큼까지만 줄이고, 잔액이 음수면 0이다.
     */
    @Transactional
    public int reclaim(long userId, String refKey, int paid, int bonus, String label) {
        locks.lockUser(userId);
        PointAccount account = repository.account(userId);
        int balance = account.balance() - paid - bonus;
        int paidBalance = Math.min(Math.max(account.paidBalance() - paid, 0), Math.max(balance, 0));
        if (!repository.insertLedger(
                userId,
                -(paid + bonus),
                paidBalance - account.paidBalance(),
                PointReason.REFUND,
                refKey,
                label,
                null)) {
            throw new IllegalStateException("같은 참조 키로 이미 되돌린 기록이 있다: " + refKey);
        }
        repository.setAccount(userId, new PointAccount(balance, paidBalance));
        return balance;
    }
}
