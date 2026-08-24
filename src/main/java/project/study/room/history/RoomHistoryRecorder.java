package project.study.room.history;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import project.study.room.entity.RoomHistory;
import project.study.room.entity.RoomParticipation;
import project.study.room.event.ParticipantJoinedEvent;
import project.study.room.event.ParticipantLeftEvent;
import project.study.room.event.RoomClosedEvent;
import project.study.room.event.RoomCreatedEvent;
import project.study.room.repository.RoomHistoryRepository;
import project.study.room.repository.RoomParticipationRepository;

/**
 * 룸 도메인 이벤트를 받아 이력 테이블에 기록한다 (분석용, best-effort).
 *
 * <p>모든 메서드는 {@code roomHistoryExecutor}(1스레드)에서 발행 순서대로 실행된다.
 * 기록 실패는 async 예외 핸들러가 로그로 남길 뿐 룸 동작에 영향을 주지 않는다.
 * 여기에 동기(@Async 없는) 리스너를 추가하면 RoomService의 글로벌 락 안에서 DB I/O가
 * 실행되므로 금지한다.
 */
@Component
@RequiredArgsConstructor
public class RoomHistoryRecorder {

    private static final Logger log = LoggerFactory.getLogger(RoomHistoryRecorder.class);

    private final RoomHistoryRepository roomHistoryRepository;
    private final RoomParticipationRepository roomParticipationRepository;

    @Async("roomHistoryExecutor")
    @EventListener
    @Transactional
    public void onRoomCreated(RoomCreatedEvent event) {
        roomHistoryRepository.save(new RoomHistory(event.roomUid(), event.createdBy(), event.createdAt()));
    }

    @Async("roomHistoryExecutor")
    @EventListener
    @Transactional
    public void onParticipantJoined(ParticipantJoinedEvent event) {
        roomParticipationRepository.save(new RoomParticipation(event.roomUid(), event.userId(), event.joinedAt()));
    }

    @Async("roomHistoryExecutor")
    @EventListener
    @Transactional
    public void onParticipantLeft(ParticipantLeftEvent event) {
        roomParticipationRepository
                .findByRoomUidAndUserIdAndLeftAtIsNull(event.roomUid(), event.userId())
                .ifPresentOrElse(
                        participation -> participation.close(event.leftAt(), event.reason()),
                        () -> log.warn("닫을 참여 기록이 없다: roomUid={}, userId={}", event.roomUid(), event.userId()));
    }

    @Async("roomHistoryExecutor")
    @EventListener
    @Transactional
    public void onRoomClosed(RoomClosedEvent event) {
        roomHistoryRepository
                .findByRoomUid(event.roomUid())
                .ifPresentOrElse(
                        room -> room.close(event.closedAt(), event.reason()),
                        () -> log.warn("닫을 방 기록이 없다: roomUid={}", event.roomUid()));
    }
}
