package interview.guide.common.ai.agent;

import java.util.List;

public record InterviewPlan(int roundNo, String roundType, int followUpCount, List<String> categoryKeys, int stepCount) {}
