package project.study.room.repository;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import project.study.room.entity.RoomHistory;

public interface RoomHistoryRepository extends JpaRepository<RoomHistory, Long> {

    Optional<RoomHistory> findByRoomUid(UUID roomUid);
}
