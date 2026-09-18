package interview.guide.common.ai;

import com.alibaba.dashscope.rerank.TextReRank;
import com.alibaba.dashscope.rerank.TextReRankOutput;
import com.alibaba.dashscope.rerank.TextReRankParam;
import lombok.extern.slf4j.Slf4j;

import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Rerank 服务：基于 DashScope text-rerank（gte-rerank-v2）对候选文档做二阶段精排。
 * <p>
 * 走 DashScope 原生 endpoint（非 OpenAI 兼容模式），故使用 DashScope SDK 的 {@link TextReRank}
 * 而非 Spring AI 的 OpenAIClient。apiKey 通过 {@link TextReRankParam#apiKey} 传入（见 HalfDuplexParamBase）。
 * <p>
 * 任意异常均宽 catch 并返回 {@code null}，调用方据此降级到原始召回顺序，不阻塞主链路。
 */
@Slf4j
public class RerankService {

    private final String apiKey;
    private final String model;

    public RerankService(String apiKey, String model) {
        this.apiKey = apiKey;
        this.model = model != null && !model.isBlank() ? model : "gte-rerank-v2";
    }

    /**
     * @param query     用户原始问题
     * @param documents 候选文档文本列表（顺序即 index）
     * @param topN      返回前 N 个
     * @return 按 rerank 分降序的 {@link RerankResult} 列表；失败返回 {@code null}
     */
    public List<RerankResult> rerank(String query, List<String> documents, int topN) {
        return rerank(query, documents, topN, null);
    }

    /**
     * @param modelOverride 非空时覆盖默认 model（来自 app.ai.rag.rerank.model）
     */
    public List<RerankResult> rerank(String query, List<String> documents, int topN, String modelOverride) {
        if (apiKey == null || apiKey.isBlank()) {
            log.warn("[RerankService] apiKey 未配置，跳过 rerank");
            return null;
        }
        if (query == null || query.isBlank() || documents == null || documents.isEmpty()) {
            return null;
        }
        String effectiveModel = (modelOverride != null && !modelOverride.isBlank()) ? modelOverride : this.model;
        try {
            TextReRankParam param = TextReRankParam.builder()
                .apiKey(apiKey)
                .model(effectiveModel)
                .query(query)
                .documents(documents)
                .topN(Math.min(topN, documents.size()))
                .returnDocuments(false)
                .build();
            List<TextReRankOutput.Result> results =
                new TextReRank().call(param).getOutput().getResults();
            if (results == null) {
                return Collections.emptyList();
            }
            return results.stream()
                .map(r -> new RerankResult(r.getIndex(), r.getRelevanceScore() != null
                    ? r.getRelevanceScore() : 0.0))
                .collect(Collectors.toList());
        } catch (Exception e) {
            log.warn("[RerankService] rerank 调用失败，降级到原始顺序: {}", e.getMessage());
            return null;
        }
    }

    public record RerankResult(Integer index, Double score) {
    }
}
