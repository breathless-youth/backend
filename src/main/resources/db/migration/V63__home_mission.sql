CREATE TABLE home_mission_progress (
    user_id bigint NOT NULL REFERENCES users(id),
    mission_version varchar(32) NOT NULL,
    completed_steps integer NOT NULL DEFAULT 0 CHECK (completed_steps BETWEEN 0 AND 2),
    PRIMARY KEY (user_id, mission_version)
);
