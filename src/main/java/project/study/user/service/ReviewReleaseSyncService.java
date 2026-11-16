package project.study.user.service;

import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import project.study.user.repository.ReviewLoginRepository;

/**
 * 심사용 로그인의 출시 버전을 App Store에 맞춘다 (BY-1312, ADR-0048). 심사 중인 앱과 출시된 앱은 버전이 같아 서버가 구분할 수 없다. 그래서
 * 스토어의 출시 버전이 저장된 값보다 높아지면 올려, 출시된 앱에서 심사용 입구가 사라지게 한다. 낮추지는 않는다.
 *
 * <p>심사용 로그인이 꺼져 있으면(출시 버전이 비어 있으면) 건드리지 않는다. 조회가 실패하면 아무것도 바꾸지 않고 다음에 다시 본다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReviewReleaseSyncService {

    private final ReviewLoginRepository repository;
    private final AppStoreVersionSource store;

    /** 올렸으면 true. */
    public boolean sync() {
        Optional<String> current = repository.find().map(ReviewLoginRepository.Settings::releasedVersion);
        if (current.isEmpty()) {
            return false;
        }
        Optional<String> released;
        try {
            released = store.releasedVersion();
        } catch (RuntimeException e) {
            log.warn("App Store 출시 버전을 읽지 못했습니다: {}", e.toString());
            return false;
        }
        if (released.isEmpty() || !AppVersions.isNewer(released.get(), current.get())) {
            return false;
        }
        boolean raised = repository.raiseReleasedVersion(current.get(), released.get());
        if (raised) {
            log.info("심사용 로그인의 출시 버전을 올렸습니다: {} -> {}", current.get(), released.get());
        }
        return raised;
    }
}
