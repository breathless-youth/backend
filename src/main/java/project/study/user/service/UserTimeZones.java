package project.study.user.service;

import java.time.DateTimeException;
import java.time.ZoneId;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import project.study.common.time.DefaultTimeZone;
import project.study.user.repository.UserRepository;

/**
 * 사용자 하루를 정하는 시간대의 조회와 해석 (ADR-0042).
 *
 * 앱이 보낸 IANA 이름을 읽을 수 있으면 그 값을 쓰고 사용자에게 저장한다.
 * 없거나 읽을 수 없으면 거절하지 않고 저장된 시간대를 쓴다. 시간대 때문에 세션 저장이 실패하면 공부 기록을 잃는다.
 * 한 번도 보내지 않은 사용자는 기본값이 서울이라 예전과 결과가 같다.
 */
@Component
@RequiredArgsConstructor
public class UserTimeZones {

    private final UserRepository userRepository;

    /** 읽을 수 있는 시간대 이름이면 그 시간대, 아니면 empty. */
    public static Optional<ZoneId> parse(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(ZoneId.of(value));
        } catch (DateTimeException e) {
            return Optional.empty();
        }
    }

    /** 저장된 시간대 — 없는 사용자면 기본값. */
    @Transactional(readOnly = true)
    public ZoneId zoneOf(Long userId) {
        return userRepository.findTimeZoneById(userId).map(ZoneId::of).orElse(DefaultTimeZone.ZONE);
    }

    /** 여러 사용자의 저장된 시간대 — 없는 사용자는 키가 없다. */
    @Transactional(readOnly = true)
    public Map<Long, ZoneId> zonesOf(Collection<Long> userIds) {
        if (userIds.isEmpty()) {
            return Map.of();
        }
        return userRepository.findTimeZonesByIdIn(userIds).stream()
                .collect(Collectors.toMap(row -> (Long) row[0], row -> ZoneId.of((String) row[1])));
    }

    /** 보낸 값이 읽히면 저장하고 그 시간대를, 아니면 저장된 시간대를 돌려준다. */
    @Transactional
    public ZoneId resolve(Long userId, String requested) {
        Optional<ZoneId> parsed = parse(requested);
        if (parsed.isEmpty()) {
            return zoneOf(userId);
        }
        userRepository.updateTimeZone(userId, parsed.get().getId());
        return parsed.get();
    }
}
