package project.study.acquisition.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import project.study.acquisition.entity.UserAcquisition;

public interface UserAcquisitionRepository extends JpaRepository<UserAcquisition, Long>, UserAcquisitionInsert {}
