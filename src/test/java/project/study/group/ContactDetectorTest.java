package project.study.group;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * 그룹 이름·소개·신청 소개 글의 연락처·링크 판정 (BY-1124). 아래 두 표는 FE에 예시 표로 그대로 넘기므로 값을 바꾸지 않는다.
 */
class ContactDetectorTest {

    @ParameterizedTest
    @ValueSource(
            strings = {
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
                "디엠 kim_99"
            })
    void 막는다(String text) {
        assertThat(ContactDetector.containsContact(text)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "9급",
                "토익 900",
                "D-100",
                "2027 수능",
                "2026-11-03부터 시작", // 숫자 8자리
                "하루 10시간 공부해요",
                "node.js 공부 모임",
                "카톡 말고 여기서 공부해요",
                "인스타 안 해요",
                "3.5시간"
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

    // 표 밖의 보강 규칙 — 표의 판정은 그대로 두고 서버만 더 엄격하거나 덜 오탐하게 한 부분

    @ParameterizedTest
    @ValueSource(
            strings = {
                "０１０-１２３４-５６７８", // 전각 숫자
                "ｗｗｗ.ｓｔｕｄｙ.me", // 전각 영문
                "\u110f\u1161\u1110\u1169\u11a8 kim1234", // NFD 한글 (자모로 풀어쓴 카톡 kim1234)
                "공부모임.com", // 한글이 붙은 도메인
                "KAKAO.COM", // 대문자
                "카톡 아이디 kim1234",
                "인스타 ID: study_kim",
                "010 - 1234 - 5678"
            })
    void 표기를_바꿔도_막는다(String text) {
        assertThat(ContactDetector.containsContact(text)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"admin123 계정 얘기", "가이드라인 100선", "DM me later", "naver.company", "화이팅 @ 7시"})
    void 연락처가_아닌_글은_통과시킨다(String text) {
        assertThat(ContactDetector.containsContact(text)).isFalse();
    }
}
