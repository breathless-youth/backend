package project.study.room.repository;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import project.study.room.entity.RoomParticipation;

public interface RoomParticipationRepository extends JpaRepository<RoomParticipation, Long> {

    Optional<RoomParticipation> findByRoomUidAndUserIdAndLeftAtIsNull(UUID roomUid, Long userId);
}
