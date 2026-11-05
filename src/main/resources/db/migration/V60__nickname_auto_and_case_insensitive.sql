-- BY-1135: 기본 닉네임 여부와 대소문자 무시 유일성.
-- nickname_auto: 가입 때 받은 기본 닉네임을 그대로 쓰고 있으면 true, 사용자가 바꾸면 false. 분석에서 "닉네임을 직접 바꿨는지"를 이 값으로 본다
-- (언어가 여섯 개라 조합 목록을 SQL에 넣어 판별할 수 없다). 지금 기본 닉네임은 '포메+숫자'뿐이다(가입 때 받은 포메{5자리}, V9가 채운 '포메' || id).
ALTER TABLE users ADD COLUMN nickname_auto boolean NOT NULL DEFAULT false;

UPDATE users SET nickname_auto = true WHERE nickname ~ '^포메[0-9]+$';

-- 영문 대소문자만 다른 닉네임(Otter / otter)을 같은 닉네임으로 막는다. 전각 영문은 앱이 NFKC로 보통 글자로 바꿔 저장하므로 여기서 함께 걸린다.
-- 가입의 on conflict do nothing과 프로필 수정의 409 변환이 이 인덱스 충돌도 그대로 처리한다.
CREATE UNIQUE INDEX users_nickname_lower_uidx ON users (lower(nickname));
