package interview.guide.modules.multiround.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class AlgorithmTemplateServiceTest {
  @Test
  @DisplayName("返回 Java、Python 和 C++ 的不可执行算法题初始模板")
  void shouldReturnTemplatesForAllSupportedLanguages() {
    AlgorithmTemplateService service = new AlgorithmTemplateService();

    assertThat(service.templateFor("JAVA")).contains("class Solution");
    assertThat(service.templateFor("PYTHON")).contains("class Solution:");
    assertThat(service.templateFor("CPP")).contains("class Solution");
  }
}
