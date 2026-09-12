package project.study.room.lease;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class TaskIdentityTest {

    @Test
    void ECS_메타데이터의_TaskARN_마지막_조각이_태스크_ID다() {
        String json = "{\"TaskARN\":\"arn:aws:ecs:ap-northeast-2:123:task/focus-makers-prod-cluster/0a1b2c3d\"}";

        assertThat(TaskIdentity.parseTaskId(json)).isEqualTo("0a1b2c3d");
    }

    @Test
    void TaskARN이_없으면_실패한다() {
        assertThatThrownBy(() -> TaskIdentity.parseTaskId("{}")).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void 메타데이터_URI가_없으면_local_UUID로_대체한다() {
        String id = TaskIdentity.resolve("");

        assertThat(id).startsWith("local-").hasSize("local-".length() + 36);
        assertThat(TaskIdentity.resolve("")).isNotEqualTo(id);
    }
}
