package project.study.acquisition.referrer;

import project.study.acquisition.entity.AcquisitionSource;

/** 판별 결과. inviteCode는 INVITE일 때만, metaAd는 암호문을 풀었을 때만 값이 있다. */
public record Attribution(AcquisitionSource source, Utm utm, String inviteCode, MetaAdDetail metaAd) {}
