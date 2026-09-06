package interview.guide.modules.multiround.dto;

import static org.assertj.core.api.Assertions.assertThat;

import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class StartMultiRoundInterviewRequestTest {
  @Test
  void shouldDeserializeNullOptionalRestartFlag() throws Exception {
    var mapper = new ObjectMapper();
    var request = mapper.readValue(
        "{\"skillId\":\"java-backend\",\"difficulty\":\"mid\",\"restart\":null}",
        StartMultiRoundInterviewRequest.class);

    assertThat(request.restart()).isNull();
  }
}
