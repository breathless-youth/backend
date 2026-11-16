package project.study.mascot.service;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import project.study.common.exception.ConflictException;
import project.study.common.exception.ErrorCode;
import project.study.common.exception.NotFoundException;
import project.study.mascot.dto.Mascot;
import project.study.mascot.dto.MascotListResponse;
import project.study.mascot.dto.MascotPurchaseResponse;
import project.study.mascot.dto.MascotResponse;
import project.study.mascot.dto.MascotUnlockType;
import project.study.mascot.dto.NextMascot;
import project.study.mascot.repository.MascotRepository;
import project.study.point.dto.PointReason;
import project.study.point.service.PointLocks;
import project.study.point.service.PointService;
import project.study.user.service.MemberGuard;

/**
 * 마스코트는 누적 순공으로 열리거나(열림 여부를 저장하지 않고 조회할 때마다 견준다) 포인트로 사서 연다(산 것은 저장한다). 쓰기는 탈퇴와
 * 겹치지 않게 포인트 사용자 락을 잡는다.
 */
@Service
@RequiredArgsConstructor
public class MascotService {

    private final MascotRepository repository;
    private final MemberGuard members;
    private final PointLocks locks;
    private final PointService points;

    @Transactional(readOnly = true)
    public MascotListResponse list(Long userId) {
        List<Mascot> all = repository.findAll();
        long total = repository.totalFocusSec(userId);
        Set<String> owned = repository.ownedCodes(userId);
        String selected = selectedOrDefault(userId, all);
        Set<String> seen = repository.seenCodes(userId);
        List<MascotResponse> mascots = all.stream()
                .map(m -> {
                    boolean unlocked = unlocked(m, total, owned);
                    return new MascotResponse(
                            m.code(),
                            m.priced() ? MascotUnlockType.POINTS : MascotUnlockType.FOCUS_TIME,
                            m.priced() ? 0 : m.unlockFocusSec(),
                            m.price(),
                            unlocked,
                            m.priced() || unlocked ? 0 : Math.max(0, m.unlockFocusSec() - total),
                            m.code().equals(selected),
                            celebrates(m) && unlocked && !seen.contains(m.code()));
                })
                .toList();
        return new MascotListResponse(total, selected, mascots);
    }

    @Transactional
    public MascotListResponse select(Long userId, String code) {
        members.requireMember(userId);
        locks.lockUser(userId);
        requireUnlocked(userId, code);
        repository.saveSelected(userId, code);
        return list(userId);
    }

    /**
     * 포인트로 사는 마스코트를 산다. 포인트 사용자 락을 먼저 잡고 가졌는지 본 뒤 잔액을 빼므로, 같은 요청이 두 번 와도 두 번째는
     * {@code MASCOT_ALREADY_OWNED}를 받고 포인트는 한 번만 빠진다.
     */
    @Transactional
    public MascotPurchaseResponse purchase(Long userId, String code) {
        members.requireMember(userId);
        locks.lockUser(userId);
        Mascot mascot = find(code);
        if (!mascot.priced()) {
            throw new ConflictException(ErrorCode.MASCOT_NOT_PURCHASABLE, "포인트로 사는 마스코트가 아닙니다");
        }
        if (repository.ownedCodes(userId).contains(code)) {
            throw new ConflictException(ErrorCode.MASCOT_ALREADY_OWNED, "이미 가진 마스코트입니다");
        }
        int balance = points.spend(userId, PointReason.MASCOT_PURCHASE, "mascot:" + code, mascot.price(), code);
        repository.insertOwned(userId, code);
        return new MascotPurchaseResponse(code, balance);
    }

    /** 지금 공부 시간으로 열려 있는 마스코트를 모두 축하 화면을 본 것으로 표시한다. 여러 번 불러도 같다. */
    @Transactional
    public MascotListResponse markUnlocksSeen(Long userId) {
        members.requireMember(userId);
        locks.lockUser(userId);
        long total = repository.totalFocusSec(userId);
        repository.markSeen(
                userId,
                repository.findAll().stream()
                        .filter(m -> !m.priced() && total >= m.unlockFocusSec())
                        .map(Mascot::code)
                        .toList());
        return list(userId);
    }

    /**
     * 모든 마스코트를 보유로 넣고 축하 화면은 본 것으로 둔다. 스토어 심사 계정을 만들 때 쓴다 (BY-1310) — 잠긴 마스코트의 세트는 상점에
     * 보이지 않아, 심사자가 모든 세트를 찾으려면 다 열려 있어야 한다.
     */
    @Transactional
    public void grantAll(long userId) {
        locks.lockUser(userId);
        List<String> codes = repository.findAll().stream().map(Mascot::code).toList();
        codes.forEach(code -> repository.insertOwnedIfAbsent(userId, code));
        repository.markSeen(userId, codes);
    }

    /** 없는 마스코트면 404 {@code MASCOT_NOT_FOUND}, 잠겨 있으면(사지 않은 포인트 마스코트 포함) 409 {@code MASCOT_LOCKED}. */
    @Transactional(readOnly = true)
    public void requireUnlocked(long userId, String code) {
        Mascot mascot = find(code);
        if (!unlocked(mascot, repository.totalFocusSec(userId), repository.ownedCodes(userId))) {
            throw new ConflictException(ErrorCode.MASCOT_LOCKED, "아직 열리지 않은 마스코트입니다");
        }
    }

    /** 공부 시간으로 여는 것 가운데 아직 잠긴, 기준이 가장 낮은 마스코트. 다 열었으면 빈 값. */
    @Transactional(readOnly = true)
    public Optional<NextMascot> next(long userId) {
        long total = repository.totalFocusSec(userId);
        Set<String> owned = repository.ownedCodes(userId);
        return repository.findAll().stream()
                .filter(m -> !m.priced() && !owned.contains(m.code()) && total < m.unlockFocusSec())
                .findFirst()
                .map(m -> new NextMascot(m.code(), m.unlockFocusSec(), m.unlockFocusSec() - total));
    }

    @Transactional(readOnly = true)
    public long totalFocusSec(long userId) {
        return repository.totalFocusSec(userId);
    }

    @Transactional(readOnly = true)
    public String selectedCode(long userId) {
        return selectedOrDefault(userId, repository.findAll());
    }

    /** 넘긴 사람마다 값이 있다. 고른 적이 없으면 첫 마스코트다. */
    @Transactional(readOnly = true)
    public Map<Long, String> selectedCodes(Collection<Long> userIds) {
        String first = repository.findAll().getFirst().code();
        Map<Long, String> selected = repository.selectedCodes(userIds);
        Map<Long, String> codes = new HashMap<>();
        userIds.forEach(userId -> codes.put(userId, selected.getOrDefault(userId, first)));
        return codes;
    }

    private Mascot find(String code) {
        return repository.findAll().stream()
                .filter(m -> m.code().equals(code))
                .findFirst()
                .orElseThrow(() -> new NotFoundException(ErrorCode.MASCOT_NOT_FOUND, "없는 마스코트입니다: " + code));
    }

    private static boolean unlocked(Mascot mascot, long totalFocusSec, Set<String> owned) {
        // 보유한 것은 공부 시간과 상관없이 열려 있다. 공부 시간으로 여는 마스코트를 보유하는 것은 심사 계정뿐이다 (BY-1310)
        return owned.contains(mascot.code()) || (!mascot.priced() && totalFocusSec >= mascot.unlockFocusSec());
    }

    /** 공부해서 연 마스코트만 축하 화면을 띄운다. 처음부터 열려 있거나 직접 산 것은 띄우지 않는다. */
    private static boolean celebrates(Mascot mascot) {
        return !mascot.priced() && mascot.unlockFocusSec() > 0;
    }

    private String selectedOrDefault(long userId, List<Mascot> all) {
        return repository.selectedCode(userId).orElseGet(() -> all.getFirst().code());
    }
}
