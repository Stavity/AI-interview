package interview.guide.modules.multiround.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record StartMultiRoundInterviewRequest(
    @NotBlank(message = "面试技能不能为空") String skillId,
    @Pattern(regexp = "junior|mid|senior", message = "面试难度必须是 junior、mid 或 senior") String difficulty,
    String jdText,
    Boolean restart
) {}
