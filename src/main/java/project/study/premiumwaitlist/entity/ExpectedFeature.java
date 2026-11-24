package project.study.premiumwaitlist.entity;

/** 사용자가 기대한다고 고른 프리미엄 기능 (BY-1392). */
public enum ExpectedFeature {
    AI_PLANNER,
    AI_REPORT,
    GROUP_MATCHING,
    /** 화면에서 뺐다(맞춤 알림은 AI_PLANNER에 합침). dev에 이미 저장된 값을 읽을 수 있게 남겨 둔다. */
    SMART_NOTIFICATION,
    /** 강력한 앱 잠금. 맞춤 알림 자리를 대신한다. */
    APP_LOCK,
    TIMER_BACKGROUND,
    CHARACTER_ITEMS
}
