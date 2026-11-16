package project.study.user.service;

import java.util.Optional;

/** 스토어에 지금 출시된 앱 버전. 읽지 못하면 빈 값이다. */
public interface AppStoreVersionSource {

    Optional<String> releasedVersion();
}
