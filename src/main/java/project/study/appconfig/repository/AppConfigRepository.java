package project.study.appconfig.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import project.study.appconfig.entity.AppConfig;

public interface AppConfigRepository extends JpaRepository<AppConfig, String> {}
