package interview.guide.common.ai.agent;

import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public class AgentPlanValidator {
  private static final Set<String> ROUND_TYPES = Set.of("ALGORITHM", "TECHNICAL_1", "TECHNICAL_2", "HR");
  public void validate(InterviewPlan plan) {
    if (plan.roundNo() < 1 || plan.roundNo() > 4 || !ROUND_TYPES.contains(plan.roundType())) throw new IllegalArgumentException("Agent 计划轮次非法");
    String expectedType = switch (plan.roundNo()) { case 1 -> "ALGORITHM"; case 2 -> "TECHNICAL_1"; case 3 -> "TECHNICAL_2"; case 4 -> "HR"; default -> throw new IllegalArgumentException("Agent 计划轮次非法"); };
    if (!expectedType.equals(plan.roundType())) throw new IllegalArgumentException("Agent 计划不能跳过或变更既定轮次");
    if (plan.followUpCount() < 0 || plan.followUpCount() > 2) throw new IllegalArgumentException("Agent 追问次数超出上限");
    if (plan.stepCount() < 1 || plan.stepCount() > 6) throw new IllegalArgumentException("Agent 规划步骤超出预算");
    if (plan.categoryKeys() == null || plan.categoryKeys().isEmpty()) throw new IllegalArgumentException("Agent 计划必须包含题目分类");
  }
  public void validate(InterviewPlan plan, AgentBudget budget) {
    validate(plan);
    budget.validateStepCount(plan.stepCount());
    budget.validateFollowUpCount(plan.followUpCount());
  }

  public void validate(InterviewPlan plan, AgentBudget budget, InterviewPlanningContext context) {
    validate(plan, budget);
    if (plan.roundNo() != context.roundNo() || !plan.roundType().equals(context.roundType())) {
      throw new IllegalArgumentException("Agent 计划不能改写当前轮次");
    }
    if (context.allowedCategoryKeys() == null || context.allowedCategoryKeys().isEmpty()
        || !context.allowedCategoryKeys().containsAll(plan.categoryKeys())) {
      throw new IllegalArgumentException("Agent 计划包含未授权题目分类");
    }
  }

}
