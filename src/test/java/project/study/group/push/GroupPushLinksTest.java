package project.study.group.push;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import project.study.common.category.StudyCategory;

class GroupPushLinksTest {

    @Test
    void 링크는_스킴_없는_경로다() {
        assertThat(GroupPushLinks.group(10L)).isEqualTo("/groups/10");
        assertThat(GroupPushLinks.applications(10L)).isEqualTo("/groups/10/applications");
        assertThat(GroupPushLinks.publicTab()).isEqualTo("/groups?tab=public");
        assertThat(GroupPushLinks.publicTab(StudyCategory.LANGUAGE)).isEqualTo("/groups?tab=public&category=LANGUAGE");
    }
}
