package interview.guide.modules.multiround.service;
import java.util.List;
/** User-facing, non-authoritative final recommendation generated from completed evidence. */
public record FinalLearningAdvice(String summary, List<String> recommendations) {}
