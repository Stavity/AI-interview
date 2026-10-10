package interview.guide.modules.learning.config;

import interview.guide.modules.learning.service.LearningEvidenceAggregator;
import interview.guide.modules.learning.service.LearningProperties;
import interview.guide.modules.learning.service.LearningRoutePlanner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class LearningEvidenceConfiguration {

  @Bean
  LearningEvidenceAggregator learningEvidenceAggregator(LearningProperties properties) {
    return new LearningEvidenceAggregator(new LearningEvidenceAggregator.Policy(
        properties.getEvidenceSessionWindow(),
        properties.getEvidenceHalfLifeDays(),
        properties.getMasteredScore(),
        properties.getMasteredMinimumSessions(),
        properties.getCorrectScore(),
        properties.getIncorrectScore()));
  }

  @Bean
  LearningRoutePlanner learningRoutePlanner() {
    return new LearningRoutePlanner();
  }
}
