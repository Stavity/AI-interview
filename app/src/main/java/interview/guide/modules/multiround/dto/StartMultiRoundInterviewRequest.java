package interview.guide.modules.multiround.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 启动多轮面试请求。
 * <p>
 * difficulty 补 {@code @NotBlank}：Jakarta 的 {@code @Pattern} 默认放过 null，
 * 原来请求体省略该字段即可通过校验，最终以「难度：null」进入 LLM 提示词。
 * <p>
 * 原有的 {@code restart} 字段从未被读取，已移除。
 */
public record StartMultiRoundInterviewRequest(
    @NotBlank(message = "面试技能不能为空") @Size(max = 64) String skillId,
    @NotBlank(message = "面试难度不能为空")
    @Pattern(regexp = "junior|mid|senior", message = "面试难度必须是 junior、mid 或 senior") String difficulty,
    @Size(max = 12_000) String jdText
) {}
