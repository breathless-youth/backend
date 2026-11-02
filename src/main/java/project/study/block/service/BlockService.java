package project.study.block.service;

import org.springframework.stereotype.Service;
import project.study.block.dto.BlockListResponse;
import project.study.common.exception.NotImplementedException;

/** 사용자 차단 REST 계약만 있는 스텁 — 로직은 다음 단계에서 채우고 {@link NotImplementedException}을 지운다 (BY-1124). */
@Service
public class BlockService {

    public BlockListResponse blocks(Long userId) {
        throw new NotImplementedException();
    }

    public void unblock(Long userId, long targetUserId) {
        throw new NotImplementedException();
    }
}
