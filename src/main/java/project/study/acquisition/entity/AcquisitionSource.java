package project.study.acquisition.entity;

/** 앱을 어디서 알고 설치했는지. 설치 정보와 딥링크 값으로 서버가 판별한다. */
public enum AcquisitionSource {
    /** 메타(인스타·페이스북) 광고. */
    META_AD,
    /** 친구 초대 링크. */
    INVITE,
    /** 스토어 검색 등 광고·링크 없이 들어온 설치. */
    ORGANIC,
    /** 위에 해당하지 않는, 값이 있는 그 밖의 링크. */
    OTHER_LINK,
    /** 설치 정보를 받지 못했거나 비어 있음. */
    UNKNOWN
}
