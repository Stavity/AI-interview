package interview.guide.modules.learning.service;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "app.learning")
public class LearningProperties {
  private int taskCompletionDelta = 5;
}
