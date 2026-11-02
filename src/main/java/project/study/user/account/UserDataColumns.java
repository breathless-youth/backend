package project.study.user.account;

import java.util.Set;

/** 처리기 없이 두는 사용자 참조 컬럼. 빠뜨림 방지 테스트(UserDataCoverageTest)가 이 목록과 처리기 목록의 합을 DB와 맞춰 본다. */
public final class UserDataColumns {

    /** 병합·탈퇴에서 건드리지 않기로 한 컬럼 (사용자 결정). */
    public static final Set<String> IGNORED =
            Set.of("amplitude_daily_event.user_id", "amplitude_user_attribute.user_id");

    /** 아직 처리기가 없는 컬럼. 처리기를 만들면 여기서 지운다. */
    public static final Set<String> PENDING = Set.of(
            "study_session.user_id",
            "active_study_session.user_id",
            "study_subject.user_id",
            "daily_completion.user_id",
            "rooms.created_by",
            "room_participations.user_id",
            "rtc_connection_stat.user_id",
            "rtc_connection_stat.peer_user_id",
            "ranking_record.user_id",
            "ranking_best.user_id");

    private UserDataColumns() {}
}
