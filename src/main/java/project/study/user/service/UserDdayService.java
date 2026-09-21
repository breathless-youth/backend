package project.study.user.service;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import project.study.common.exception.BadRequestException;
import project.study.user.dto.DdayRequest;
import project.study.user.dto.DdayResponse;
import project.study.user.entity.UserDday;
import project.study.user.repository.UserDdayRepository;

/** 홈 D-Day 조회·upsert·삭제. 유저당 1개라 목록·소유 검증이 없고 userId 하나로 끝난다. */
@Service
@RequiredArgsConstructor
public class UserDdayService {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private final UserDdayRepository ddayRepository;
    private final Clock clock;

    @Transactional(readOnly = true)
    public Optional<DdayResponse> find(Long userId) {
        return ddayRepository.findByUserId(userId).map(UserDdayService::toResponse);
    }

    /** 있으면 덮어쓰고 없으면 만든다 — 유저당 1개라 upsert 하나면 충분하다. */
    @Transactional
    public DdayResponse save(Long userId, DdayRequest request) {
        LocalDate today = LocalDate.ofInstant(clock.instant(), KST);
        if (request.targetDate().isBefore(today)) {
            throw new BadRequestException("목표 날짜는 오늘 이후여야 합니다");
        }
        String title = request.title().strip();
        UserDday dday = ddayRepository
                .findByUserId(userId)
                .map(existing -> {
                    existing.update(title, request.targetDate());
                    return existing;
                })
                .orElseGet(() -> ddayRepository.save(new UserDday(userId, title, request.targetDate())));
        return toResponse(dday);
    }

    /** 없어도 성공 — 삭제는 멱등이다. */
    @Transactional
    public void delete(Long userId) {
        ddayRepository.deleteByUserId(userId);
    }

    private static DdayResponse toResponse(UserDday dday) {
        return new DdayResponse(dday.getTitle(), dday.getTargetDate());
    }
}
