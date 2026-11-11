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
import project.study.mascot.dto.MascotResponse;
import project.study.mascot.dto.NextMascot;
import project.study.mascot.repository.MascotRepository;
import project.study.point.service.PointLocks;
import project.study.user.service.MemberGuard;

/**
 * 마스코트는 누적 순공으로 열린다. 열림 여부는 저장하지 않고 조회할 때마다 누적 순공과 기준을 견줘 정한다. 쓰기는 탈퇴와 겹치지 않게 포인트 사용자 락을
 * 잡는다.
 */
@Service
@RequiredArgsConstructor
public class MascotService {

    private final MascotRepository repository;
    private final MemberGuard members;
    private final PointLocks locks;

    @Transactional(readOnly = true)
    public MascotListResponse list(Long userId) {
        members.requireMember(userId);
        List<Mascot> all = repository.findAll();
        long total = repository.totalFocusSec(userId);
        String selected = selectedOrDefault(userId, all);
        Set<String> seen = repository.seenCodes(userId);
        List<MascotResponse> mascots = all.stream()
                .map(m -> {
                    boolean unlocked = total >= m.unlockFocusSec();
                    return new MascotResponse(
                            m.code(),
                            m.unlockFocusSec(),
                            unlocked,
                            Math.max(0, m.unlockFocusSec() - total),
                            m.code().equals(selected),
                            unlocked && m.unlockFocusSec() > 0 && !seen.contains(m.code()));
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

    /** 지금 열려 있는 마스코트를 모두 축하 화면을 본 것으로 표시한다. 여러 번 불러도 같다. */
    @Transactional
    public MascotListResponse markUnlocksSeen(Long userId) {
        members.requireMember(userId);
        locks.lockUser(userId);
        long total = repository.totalFocusSec(userId);
        repository.markSeen(
                userId,
                repository.findAll().stream()
                        .filter(m -> total >= m.unlockFocusSec())
                        .map(Mascot::code)
                        .toList());
        return list(userId);
    }

    /** 없는 마스코트면 404 {@code MASCOT_NOT_FOUND}, 잠겨 있으면 409 {@code MASCOT_LOCKED}. */
    @Transactional(readOnly = true)
    public void requireUnlocked(long userId, String code) {
        Mascot mascot = repository.findAll().stream()
                .filter(m -> m.code().equals(code))
                .findFirst()
                .orElseThrow(() -> new NotFoundException(ErrorCode.MASCOT_NOT_FOUND, "없는 마스코트입니다: " + code));
        if (repository.totalFocusSec(userId) < mascot.unlockFocusSec()) {
            throw new ConflictException(ErrorCode.MASCOT_LOCKED, "아직 열리지 않은 마스코트입니다");
        }
    }

    /** 아직 잠긴 것 가운데 기준이 가장 낮은 마스코트. 다 열었으면 빈 값. */
    @Transactional(readOnly = true)
    public Optional<NextMascot> next(long userId) {
        long total = repository.totalFocusSec(userId);
        return repository.findAll().stream()
                .filter(m -> total < m.unlockFocusSec())
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

    private String selectedOrDefault(long userId, List<Mascot> all) {
        return repository.selectedCode(userId).orElseGet(() -> all.getFirst().code());
    }
}
