package project.study.push.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import project.study.push.dto.PushTokenRequest;
import project.study.push.repository.PushTokenRepository;

/** 기기 토큰 등록. 앱이 실행·토큰 갱신·사용자 변경 때마다 부르므로 멱등이어야 한다. */
@Service
@RequiredArgsConstructor
public class PushTokenService {

    private final PushTokenRepository pushTokenRepository;

    /** 다른 사용자에게 붙어 있던 토큰이면 지금 사용자로 옮긴다 — 같은 기기에서 다른 계정으로 로그인한 경우다. */
    @Transactional
    public void register(Long userId, PushTokenRequest request) {
        pushTokenRepository.upsert(userId, request.token(), request.platform().name(), request.notificationsEnabled());
    }
}
