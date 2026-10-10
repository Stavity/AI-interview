package interview.guide.modules.learning.service;

import static org.assertj.core.api.Assertions.assertThat;

import interview.guide.modules.learning.model.LearningSkillSourceEntity;
import interview.guide.modules.questiontag.model.QuestionKnowledgeNodeEntity;
import java.util.List;
import org.junit.jupiter.api.Test;

class LearningPathResourceMatcherTest {

  private final LearningPathResourceMatcher matcher = new LearningPathResourceMatcher();

  @Test
  void matchesJavaNodeOnlyToTheJavaLearningCategory() {
    QuestionKnowledgeNodeEntity node = QuestionKnowledgeNodeEntity.create(1,
        "PROGRAMMING_LANGUAGE.JAVA.CONCURRENCY.AQS", "AQS", null, "CONCEPT", null, true, 1);
    LearningSkillSourceEntity java = source(1L, "JAVA", "Java");
    LearningSkillSourceEntity redis = source(2L, "REDIS", "Redis");

    assertThat(matcher.match(node, List.of(redis, java)))
        .extracting(match -> match.source().getId())
        .containsExactly(1L);
    assertThat(matcher.match(node, List.of(redis, java)).getFirst().reason())
        .contains("目录代码直接匹配");
  }

  @Test
  void returnsNoBroadResourceWhenTheCatalogueHasNoExactMatch() {
    QuestionKnowledgeNodeEntity node = QuestionKnowledgeNodeEntity.create(1,
        "PROGRAMMING_LANGUAGE.JAVA.CONCURRENCY.AQS", "AQS", null, "CONCEPT", null, true, 1);

    assertThat(matcher.match(node, List.of(source(2L, "PROJECT", "项目经历")))).isEmpty();
  }

  private static LearningSkillSourceEntity source(long id, String category, String name) {
    LearningSkillSourceEntity source = LearningSkillSourceEntity.active("java-backend", category,
        name, "", "hash");
    source.setId(id);
    return source;
  }
}
