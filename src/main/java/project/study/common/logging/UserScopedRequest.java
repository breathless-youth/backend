package project.study.common.logging;

/**
 * 요청 body에 유저 ID를 담는 DTO가 구현하는 마커.
 *
 * <p>인증(JWT)이 파킹된 동안 POST 요청의 유저는 body 안에만 있다. 서블릿 필터는 body를 읽지 않는
 * 것이 원칙이라, 역직렬화 직후 {@link UserIdBodyAdvice}가 이 인터페이스로 판별해 MDC에 싣는다.
 * record의 {@code userId} 컴포넌트가 그대로 접근자가 되므로 구현 선언만 붙이면 된다.
 */
public interface UserScopedRequest {

    Long userId();
}
