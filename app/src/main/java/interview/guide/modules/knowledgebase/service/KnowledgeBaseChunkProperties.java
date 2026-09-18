package interview.guide.modules.knowledgebase.service;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 知识库分块参数配置。
 * <p>
 * 字符级切分（非 token 级），避免 CL100K_BASE tokenizer 与 Qwen embedding 模型不匹配的问题。
 * 默认 targetChunkChars=1000 约等于 500-700 个 Qwen tokens，处于 DashScope text-embedding-v3 的 2048 token 输入限内。
 */
@Data
@Component
@ConfigurationProperties(prefix = "app.ai.rag.chunk")
public class KnowledgeBaseChunkProperties {

    /** 每个 chunk 的目标字符数上限 */
    private int targetChunkChars = 1000;

    /** 相邻 chunk 之间的重叠字符数，用于保留跨边界语义 */
    private int overlapChars = 200;

    /** 低于此字符数的 chunk 会被丢弃 */
    private int minChunkChars = 20;

    /** 是否把当前章节标题文本保留进 chunk 内容 */
    private boolean includeHeadingInChunk = true;
}
