package project.study.group;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * 그룹 이름·소개·신청 소개 글의 연락처·링크 판정 (BY-1124). 각 표의 "브리프 표" 묶음은 FE에 예시 표로 그대로 넘기므로 값을 바꾸지 않는다. "추가 예시"
 * 묶음은 서버 쪽 보강 규칙을 지키는 테스트다.
 */
class ContactDetectorTest {

    @ParameterizedTest
    @ValueSource(
            strings = {
                // ---- 브리프 표 (FE 전달, 값 변경 금지) ----
                // 링크
                "https://open.kakao.com/o/abc",
                // www
                "www.studywith.me",
                // 도메인
                "studygroup.kr 으로 와요",
                "naver.com",
                // 이메일
                "kim1234@gmail.com",
                // 숫자 9자리 이상 (- 포함, 띄어쓰기 포함, 붙어 있음)
                "010-1234-5678",
                "010 1234 5678",
                "123456789",
                // @ 아이디
                "@study_kim",
                // 연락 수단 말 + 아이디
                "카톡 kim1234",
                "인스타: study_kim",
                "오픈채팅 abc123",
                "텔레그램 kimstudy",
                "디엠 kim_99",
                // ---- 추가 예시: 숫자 구분자 하나 (-, 공백·줄바꿈·탭, .) ----
                "010.1234.5678",
                "010\n1234\n5678",
                "010\t1234\t5678",
                // ---- 추가 예시: 연락 수단 말 전부 (앞 조건을 너무 조이지 않았는지) ----
                "dm kim99",
                "라인 abc123",
                "디코 abc123",
                "카카오톡 abc123",
                "오픈톡 abc123",
                "디스코드 abc123",
                "인스타그램 abc123",
                "텔레 abc123",
                "카톡 아이디 kim1234",
                "인스타 ID: study_kim",
                "카카오 아이디 kim1234",
                "kakao id kim1234",
                "갠톡 kim1234",
                "insta kim_study",
                // ---- 추가 예시: 영어 연락 수단 말 직접 케이스 (kakaotalk·instagram, 아이디 표시 포함) ----
                "kakaotalk kim1234",
                "instagram abc_12",
                "KakaoTalk 아이디 abc12",
                // ---- 추가 예시: 대소문자·한글 도메인 ----
                "KAKAO.COM",
                "공부모임.com",
                "ｗｗｗ.ｓｔｕｄｙ.me", // 전각 영문 (NFKC 없이도 도메인 규칙으로 잡힌다)
                // ---- 추가 예시: NFKC로 맞춰야만 잡히는 것 ----
                "０１０-１２３４-５６７８", // 전각 숫자
                "ｗｗｗ．ａ", // 전각 영문 + 전각 마침표
                "\u110f\u1161\u1110\u1169\u11a8 kim1234" // 카톡을 자모로 풀어쓴 NFD 한글
            })
    void 막는다(String text) {
        assertThat(ContactDetector.containsContact(text)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                // ---- 브리프 표 (FE 전달, 값 변경 금지) ----
                "9급",
                "토익 900",
                "D-100",
                "2027 수능",
                "2026-11-03부터 시작", // 숫자 8자리
                "하루 10시간 공부해요",
                "node.js 공부 모임",
                "카톡 말고 여기서 공부해요",
                "인스타 안 해요",
                "3.5시간",
                // ---- 추가 예시: 연락 수단 말 뒤가 숫자뿐이면 아이디가 아니다 ----
                "카톡 100명 모임",
                "인스타 100일 챌린지",
                "라인 100선",
                // ---- 추가 예시: 날짜·기간 표기 ----
                "2026-11-03 - 2027-02-01", // 구분자가 연달아 오면 숫자열이 끊긴다
                "2026.11.03 시작",
                // ---- 추가 예시: 단어 속 dm·라인, 도메인 경계, 뜬 @ ----
                "admin123 계정 얘기",
                "가이드라인 100선",
                "DM me later",
                "naver.company",
                "화이팅 @ 7시",
                "@10시 출석", // @ 뒤에 영문자가 없으면 아이디가 아니다
                "installation guide", // insta는 영어 단어 속에서는 연락 수단이 아니다
                // 카카오·kakao는 회사 이름이라 뒤에 아이디 표시가 없으면 연락 수단이 아니다
                "카카오 backend 준비",
                "카카오 android 스터디",
                "kakao backend 준비"
            })
    void 통과시킨다(String text) {
        assertThat(ContactDetector.containsContact(text)).isFalse();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "\n"})
    void null_빈_문자열_공백은_통과시킨다(String text) {
        assertThat(ContactDetector.containsContact(text)).isFalse();
    }
}
