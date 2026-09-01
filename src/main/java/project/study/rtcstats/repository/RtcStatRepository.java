package project.study.rtcstats.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import project.study.rtcstats.entity.RtcConnectionStat;

public interface RtcStatRepository extends JpaRepository<RtcConnectionStat, Long> {}
