package project.study.group;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

class InviteCodesTest {

    @Test
    void 알파벳은_헷갈리는_0_O_1_I_L을_뺀_31자다() {
        assertThat(InviteCodes.ALPHABET)
                .isEqualTo("23456789ABCDEFGHJKMNPQRSTUVWXYZ")
                .hasSize(31);
        assertThat(InviteCodes.ALPHABET).doesNotContain("0", "O", "1", "I", "L");
    }

    @Test
    void 만든_코드는_알파벳만_쓴_6자다() {
        Random random = new Random(7);

        for (int i = 0; i < 500; i++) {
            assertThat(InviteCodes.generate(random)).hasSize(6).matches("[" + InviteCodes.ALPHABET + "]{6}");
        }
    }

    @Test
    void 알파벳_31자가_모두_나온다() {
        SecureRandom random = new SecureRandom();
        StringBuilder seen = new StringBuilder();
        for (int i = 0; i < 2000; i++) {
            seen.append(InviteCodes.generate(random));
        }

        assertThat(seen.toString().chars().distinct().count()).isEqualTo(31);
    }

    @Test
    void canonical은_앞뒤_공백을_떼고_대문자로_바꾼다() {
        assertThat(InviteCodes.canonical(" k7m2pq ")).isEqualTo("K7M2PQ");
        assertThat(InviteCodes.canonical("K7M2PQ")).isEqualTo("K7M2PQ");
    }

    @Test
    void canonical은_null이면_null이다() {
        assertThat(InviteCodes.canonical(null)).isNull();
    }

    @Test
    void 이미_쓰는_코드면_다시_뽑는다() {
        List<String> asked = new ArrayList<>();

        String code = InviteCodes.issue(new Random(1), candidate -> {
            asked.add(candidate);
            return asked.size() < 3;
        });

        assertThat(asked).hasSize(3).last().isEqualTo(code);
    }

    @Test
    void 열_번을_뽑아도_다_쓰는_코드면_실패한다() {
        List<String> asked = new ArrayList<>();

        assertThatThrownBy(() -> InviteCodes.issue(new Random(1), candidate -> {
                    asked.add(candidate);
                    return true;
                }))
                .isInstanceOf(IllegalStateException.class);

        assertThat(asked).hasSize(10);
    }
}
