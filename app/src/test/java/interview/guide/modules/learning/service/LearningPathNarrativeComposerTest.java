package interview.guide.modules.learning.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import interview.guide.common.ai.LlmProviderRegistry;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class LearningPathNarrativeComposerTest {

  @Test
  void staysOnTheRuleSummaryWhenAiIsDisabled() {
    LlmProviderRegistry registry = mock(LlmProviderRegistry.class);
    LearningProperties properties = new LearningProperties();
    properties.setPathAiEnabled(false);
    LearningPathNarrativeComposer composer = new LearningPathNarrativeComposer(registry, properties);

    var result = composer.compose("Java 面试", List.of(), Map.of(), "规则摘要");

    assertThat(result).isEqualTo(new LearningPathNarrativeComposer.Composition("规则摘要", "RULE"));
    verifyNoInteractions(registry);
  }

  @Test
  void fallsBackToRuleSummaryWhenAiProviderFails() {
    LlmProviderRegistry registry = mock(LlmProviderRegistry.class);
    when(registry.getPlainChatClient()).thenThrow(new IllegalStateException("provider unavailable"));
    LearningProperties properties = new LearningProperties();
    properties.setPathAiEnabled(true);
    LearningPathNarrativeComposer composer = new LearningPathNarrativeComposer(registry, properties);
    var route = List.of(new LearningRoutePlanner.RouteStep("A", null, 10, 1, false, "薄弱点"));

    var result = composer.compose("Java 面试", route, Map.of(), "规则摘要");

    assertThat(result).isEqualTo(new LearningPathNarrativeComposer.Composition("规则摘要", "RULE"));
  }
}
