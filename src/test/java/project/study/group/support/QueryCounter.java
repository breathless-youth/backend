package project.study.group.support;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.jdbc.datasource.DelegatingDataSource;

/**
 * 이 스레드가 낸 SQL 문 수를 센다 (BY-1124 멤버 책상) — 그룹·사람 수가 늘어도 쿼리 수가 그대로인지(사람마다 쿼리하지 않는지) 보는 테스트용이다.
 * DataSource 빈을 감싸 연결의 문 준비 호출을 센다. JdbcClient·JPA 모두 이 길을 지나고, 트랜잭션 격리 수준·읽기 전용 설정은 드라이버 안에서 처리돼
 * 세지 않는다. MockMvc 요청은 테스트 스레드에서 돌아 같은 스레드로 센다.
 */
public class QueryCounter implements BeanPostProcessor {

    private static final Set<String> STATEMENT_METHODS = Set.of("prepareStatement", "createStatement", "prepareCall");

    private static final ThreadLocal<AtomicInteger> CURRENT = new ThreadLocal<>();

    /** action을 실행하는 동안 이 스레드가 낸 SQL 문 수. */
    public static int count(Runnable action) {
        AtomicInteger counter = new AtomicInteger();
        CURRENT.set(counter);
        try {
            action.run();
        } finally {
            CURRENT.remove();
        }
        return counter.get();
    }

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) {
        if (bean instanceof DataSource dataSource && !(bean instanceof CountingDataSource)) {
            return new CountingDataSource(dataSource);
        }
        return bean;
    }

    private static Connection counting(Connection target) {
        return (Connection) Proxy.newProxyInstance(
                Connection.class.getClassLoader(), new Class<?>[] {Connection.class}, (proxy, method, args) -> {
                    AtomicInteger counter = CURRENT.get();
                    if (counter != null && STATEMENT_METHODS.contains(method.getName())) {
                        counter.incrementAndGet();
                    }
                    try {
                        return method.invoke(target, args);
                    } catch (InvocationTargetException e) {
                        throw e.getCause();
                    }
                });
    }

    /** Boot의 DataSource 풀기(DataSourceUnwrapper)는 DelegatingDataSource를 알아서 Hikari 지표·상태 확인도 그대로다. */
    private static final class CountingDataSource extends DelegatingDataSource implements AutoCloseable {

        private CountingDataSource(DataSource target) {
            super(target);
        }

        @Override
        public Connection getConnection() throws SQLException {
            return counting(super.getConnection());
        }

        @Override
        public Connection getConnection(String username, String password) throws SQLException {
            return counting(super.getConnection(username, password));
        }

        /** 감싼 빈에서도 컨텍스트가 닫힐 때 풀이 닫히게 한다(추론된 destroy 메서드). */
        @Override
        public void close() throws Exception {
            if (getTargetDataSource() instanceof AutoCloseable closeable) {
                closeable.close();
            }
        }
    }
}
