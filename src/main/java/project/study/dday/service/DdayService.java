package project.study.dday.service;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import project.study.common.exception.BadRequestException;
import project.study.dday.dto.DdayRequest;
import project.study.dday.dto.DdayResponse;
import project.study.dday.entity.Dday;
import project.study.dday.repository.DdayRepository;

/** 홈 D-Day 조회·upsert·삭제. 유저당 1개라 목록·소유 검증이 없고 userId 하나로 끝난다. */
@Service
@RequiredArgsConstructor
public class DdayService {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private final DdayRepository ddayRepository;
    private final Clock clock;

    @Transactional(readOnly = true)
    public Optional<DdayResponse> find(Long userId) {
        return ddayRepository.findByUserId(userId).map(DdayService::toResponse);
    }

    /** 있으면 덮어쓰고 없으면 만든다 — DB의 ON CONFLICT 한 문장이라 동시 PUT에도 행은 하나다. */
    @Transactional
    public DdayResponse save(Long userId, DdayRequest request) {
        LocalDate today = LocalDate.ofInstant(clock.instant(), KST);
        if (request.targetDate().isBefore(today)) {
            throw new BadRequestException("목표 날짜는 오늘 이후여야 합니다");
        }
        String title = request.title().strip();
        ddayRepository.upsert(userId, title, request.targetDate());
        return new DdayResponse(title, request.targetDate());
    }

    /** 없어도 성공 — 삭제는 멱등이다. */
    @Transactional
    public void delete(Long userId) {
        ddayRepository.deleteByUserId(userId);
    }

    private static DdayResponse toResponse(Dday dday) {
        return new DdayResponse(dday.getTitle(), dday.getTargetDate());
    }
}
