package project.study.user.account;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import project.study.TestcontainersConfiguration;

/** users를 가리키는 컬럼이 새로 생기면 처리기(또는 IGNORED/PENDING)에 등록하지 않은 채 넘어가지 못하게 한다. */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class UserDataCoverageTest {

    @Autowired
    JdbcClient jdbc;

    @Autowired
    List<UserDataHandler> handlers;

    @Test
    void usersReferencingColumnsAreAllCovered() {
        Set<String> db = new HashSet<>();
        db.addAll(jdbc.sql("""
                        SELECT cl.relname || '.' || a.attname
                        FROM pg_constraint c
                        JOIN pg_class cl ON cl.oid = c.conrelid
                        JOIN pg_namespace ns ON ns.oid = cl.relnamespace AND ns.nspname = 'public'
                        JOIN pg_attribute a ON a.attrelid = c.conrelid AND a.attnum = ANY (c.conkey)
                        WHERE c.contype = 'f' AND c.confrelid = 'users'::regclass""").query(String.class).list());
        db.addAll(jdbc.sql("""
                        SELECT table_name || '.' || column_name
                        FROM information_schema.columns
                        WHERE table_schema = 'public'
                          AND table_name NOT IN ('users', 'flyway_schema_history')
                          AND (column_name = 'user_id' OR column_name LIKE '%\\_user\\_id')""").query(String.class).list());
        db.remove("users.id");

        Set<String> covered = new HashSet<>();
        handlers.forEach(h -> covered.addAll(h.columns()));
        covered.addAll(UserDataColumns.IGNORED);
        covered.addAll(UserDataColumns.PENDING);

        assertThat(db).isEqualTo(covered);
    }

    @Test
    void noColumnIsOwnedTwice() {
        List<String> all = new ArrayList<>();
        handlers.forEach(h -> all.addAll(h.columns()));
        all.addAll(UserDataColumns.IGNORED);
        all.addAll(UserDataColumns.PENDING);

        assertThat(all).doesNotHaveDuplicates();
    }
}
