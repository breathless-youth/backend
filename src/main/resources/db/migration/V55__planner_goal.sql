-- 기본값과 개별값은 별도 행이다. 0은 명시적인 목표이고 행 없음만 미설정이다.
CREATE TABLE planner_default_goal (
    user_id bigint PRIMARY KEY REFERENCES users(id),
    goal_sec integer NOT NULL CHECK (goal_sec BETWEEN 0 AND 86400 AND goal_sec % 1800 = 0)
);

CREATE TABLE planner_daily_goal (
    user_id bigint NOT NULL REFERENCES users(id),
    goal_date date NOT NULL,
    goal_sec integer NOT NULL CHECK (goal_sec BETWEEN 0 AND 86400 AND goal_sec % 1800 = 0),
    PRIMARY KEY (user_id, goal_date)
);
