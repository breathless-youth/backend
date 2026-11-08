ALTER TABLE planner_default_goal DROP CONSTRAINT planner_default_goal_goal_sec_check;
ALTER TABLE planner_default_goal ADD CHECK (goal_sec BETWEEN 0 AND 86400);
ALTER TABLE planner_daily_goal DROP CONSTRAINT planner_daily_goal_goal_sec_check;
ALTER TABLE planner_daily_goal ADD CHECK (goal_sec BETWEEN 0 AND 86400);
