package project.study.metrics.report;

import java.util.Set;

/** 리포트가 읽는 Amplitude 이벤트 키 (스펙 §5·§7.3). 속성값 키(`:on`·`:landscape`)는 적재가 만든다. */
public final class AmplitudeKeys {

    public static final String APP_LAUNCHED = "app_launched";
    public static final String APP_FOREGROUNDED = "app_foregrounded";
    public static final String AMBIENT_SOUND = "ambient_sound_changed";
    public static final String PLANNER = "planner_opened";
    public static final String INVITE_SHARED = "invite_shared";
    public static final String GUIDE_FINISHED = "guide_finished";
    public static final String RANKING = "ranking_board_viewed";
    public static final String DISTRACTED = "study_session_distracted";
    public static final String SIMPLE_MODE_ON = "session_simple_mode_toggled:on";
    public static final String LANDSCAPE = "session_orientation_changed:landscape";
    public static final String CAMERA_ON = "social_room_camera_toggled:on";
    public static final String INVITE_OPENED = "invite_deep_link_opened";
    public static final String RECORDS_DATE = "records_date_selected";
    public static final String PUSH_OPENED = "push_notification_opened";
    /** 세션 시작 — 첫 세션 실험의 D7 판정(가입 7일 뒤 그날 돌아왔나)에 쓴다. */
    public static final String STUDY_SESSION_STARTED = "study_session_started";

    /** "앱을 열었다"로 보는 키 — 어제 앱만 열고 공부 안 한 사람 수에 쓴다. */
    public static final Set<String> APP_OPEN = Set.of(APP_LAUNCHED, APP_FOREGROUNDED);

    public static final Set<String> ALL = Set.of(
            APP_LAUNCHED,
            APP_FOREGROUNDED,
            AMBIENT_SOUND,
            PLANNER,
            INVITE_SHARED,
            GUIDE_FINISHED,
            RANKING,
            DISTRACTED,
            SIMPLE_MODE_ON,
            LANDSCAPE,
            CAMERA_ON,
            INVITE_OPENED,
            RECORDS_DATE,
            PUSH_OPENED,
            STUDY_SESSION_STARTED);

    private AmplitudeKeys() {}
}
