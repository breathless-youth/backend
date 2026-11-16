package project.study.point.service;

import java.security.PublicKey;
import java.util.Map;

/** AdMob이 광고 보상 콜백에 서명하는 공개 키 목록(키 ID → 키). */
public interface AdMobKeySource {

    Map<Long, PublicKey> fetch();
}
