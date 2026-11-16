-- BY-1314: 포인트를 쌓기 시작한 날. 포인트 기능이 든 앱 버전이 App Store에 출시된 것을 서버가 처음 본 날(서울 날짜)을 적는다.
-- 운영 설정 app.points.launch.version이 있을 때만 쓰고, 행이 없으면 아직 출시 전이라 포인트가 쌓이지 않는다.
CREATE TABLE point_launch (
    version varchar(32) PRIMARY KEY,
    started_on date NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now()
);
