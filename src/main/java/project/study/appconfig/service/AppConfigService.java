package project.study.appconfig.service;

import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import project.study.appconfig.entity.AppConfig;
import project.study.appconfig.repository.AppConfigRepository;

/**
 * 운영 설정 조회. 캐시하지 않는다 — 행이 몇 개뿐이고, SQL로 바꾼 값이 다음 요청부터 바로 반영돼야 한다.
 */
@Service
@RequiredArgsConstructor
public class AppConfigService {

    private final AppConfigRepository appConfigRepository;

    /** 값이 정확히 {@code "true"}일 때만 켜진 것으로 본다 — 키가 없거나 공백·오타가 섞이면 꺼진 쪽이 안전하다. */
    @Transactional(readOnly = true)
    public boolean isEnabled(String key) {
        return find(key).map("true"::equals).orElse(false);
    }

    /** 앞뒤 공백을 뗀 값. 없거나 빈 문자열이면 비어 있다 — 붙여 넣은 링크의 줄바꿈이 앱으로 새지 않게 한다. */
    @Transactional(readOnly = true)
    public Optional<String> getString(String key) {
        return find(key).map(String::strip).filter(value -> !value.isEmpty());
    }

    private Optional<String> find(String key) {
        return appConfigRepository.findById(key).map(AppConfig::getValue);
    }
}
