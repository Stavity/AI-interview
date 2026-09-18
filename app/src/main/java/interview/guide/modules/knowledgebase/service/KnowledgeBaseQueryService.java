package interview.guide.modules.knowledgebase.service;

import interview.guide.common.ai.LlmProviderRegistry;
import interview.guide.common.ai.PromptSecurityConstants;
import interview.guide.common.ai.RerankService;
import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.modules.knowledgebase.model.QueryRequest;
import interview.guide.modules.knowledgebase.model.QueryResponse;
import interview.guide.modules.knowledgebase.repository.KeywordSearchRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.ai.document.Document;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Service;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 知识库查询服务
 * 基于向量搜索的RAG问答
 */
@Slf4j
@Service
public class KnowledgeBaseQueryService {
    private static final String NO_RESULT_RESPONSE = "抱歉，在选定的知识库中未检索到相关信息。请换一个更具体的关键词或补充上下文后再试。";
    private static final int STREAM_PROBE_CHARS = 120;
    private static final int MAX_REWRITE_HISTORY_CHAR = 200;

    private final LlmProviderRegistry llmProviderRegistry;
    private final KnowledgeBaseVectorService vectorService;
    private final KnowledgeBaseListService listService;
    private final KnowledgeBaseCountService countService;
    private final KeywordSearchRepository keywordSearchRepository;
    private final KnowledgeBaseQueryProperties queryProperties;
    private final PromptTemplate systemPromptTemplate;
    private final PromptTemplate userPromptTemplate;
    private final PromptTemplate rewritePromptTemplate;
    private final boolean rewriteEnabled;
    private final int shortQueryLength;
    private final int topkShort;
    private final int topkMedium;
    private final int topkLong;
    private final double minScoreShort;
    private final double minScoreDefault;

    public KnowledgeBaseQueryService(
            LlmProviderRegistry llmProviderRegistry,
            KnowledgeBaseVectorService vectorService,
            KnowledgeBaseListService listService,
            KnowledgeBaseCountService countService,
            KeywordSearchRepository keywordSearchRepository,
            KnowledgeBaseQueryProperties queryProperties,
            ResourceLoader resourceLoader) throws IOException {
        this.llmProviderRegistry = llmProviderRegistry;
        this.vectorService = vectorService;
        this.listService = listService;
        this.countService = countService;
        this.keywordSearchRepository = keywordSearchRepository;
        this.queryProperties = queryProperties;
        this.systemPromptTemplate = new PromptTemplate(
            resourceLoader.getResource(queryProperties.getSystemPromptPath())
                .getContentAsString(StandardCharsets.UTF_8)
        );
        this.userPromptTemplate = new PromptTemplate(
            resourceLoader.getResource(queryProperties.getUserPromptPath())
                .getContentAsString(StandardCharsets.UTF_8)
        );
        this.rewritePromptTemplate = new PromptTemplate(
            resourceLoader.getResource(queryProperties.getRewritePromptPath())
                .getContentAsString(StandardCharsets.UTF_8)
        );
        this.rewriteEnabled = queryProperties.getRewrite().isEnabled();
        this.shortQueryLength = queryProperties.getSearch().getShortQueryLength();
        this.topkShort = queryProperties.getSearch().getTopkShort();
        this.topkMedium = queryProperties.getSearch().getTopkMedium();
        this.topkLong = queryProperties.getSearch().getTopkLong();
        this.minScoreShort = queryProperties.getSearch().getMinScoreShort();
        this.minScoreDefault = queryProperties.getSearch().getMinScoreDefault();
    }

    private ChatClient getChatClient() {
        return llmProviderRegistry.getDefaultChatClient();
    }

    /**
     * 基于单个知识库回答用户问题
     *
     * @param knowledgeBaseId 知识库ID
     * @param question 用户问题
     * @return AI回答
     */
    public String answerQuestion(Long knowledgeBaseId, String question) {
        return answerQuestion(List.of(knowledgeBaseId), question);
    }

    /**
     * 基于多个知识库回答用户问题（RAG）
     *
     * @param knowledgeBaseIds 知识库ID列表
     * @param question 用户问题
     * @return AI回答
     */
    public String answerQuestion(List<Long> knowledgeBaseIds, String question) {
        log.info("收到知识库提问: kbIds={}, question={}", knowledgeBaseIds, question);
        if (knowledgeBaseIds == null || knowledgeBaseIds.isEmpty() || normalizeQuestion(question).isBlank()) {
            return NO_RESULT_RESPONSE;
        }

        countService.updateQuestionCounts(knowledgeBaseIds);

        QueryContext queryContext = buildQueryContext(question, List.of());
        List<Document> relevantDocs = retrieveRelevantDocs(queryContext, knowledgeBaseIds);

        if (!hasEffectiveHit(relevantDocs)) {
            return NO_RESULT_RESPONSE;
        }

        String context = buildContext(relevantDocs);

        String systemPrompt = buildSystemPrompt();
        String userPrompt = buildUserPrompt(context, question);

        try {
            String answer = getChatClient().prompt()
                    .system(systemPrompt)
                    .user(userPrompt)
                    .call()
                    .content();
            answer = normalizeAnswer(answer);

            log.info("知识库问答完成: kbIds={}", knowledgeBaseIds);
            return answer;

        } catch (Exception e) {
            log.error("知识库问答失败: {}", e.getMessage(), e);
            throw new BusinessException(ErrorCode.KNOWLEDGE_BASE_QUERY_FAILED, "知识库查询失败：" + e.getMessage());
        }
    }

    /**
     * 构建系统提示词
     */
    private String buildSystemPrompt() {
        return systemPromptTemplate.render()
            + PromptSecurityConstants.ANTI_INJECTION_INSTRUCTION;
    }

    /**
     * 构建用户提示词
     */
    private String buildUserPrompt(String context, String question) {
        Map<String, Object> variables = new HashMap<>();
        variables.put("context", context);
        variables.put("question", question);
        return userPromptTemplate.render(variables);
    }

    /**
     * 上下文装配（small-to-big + section-aware）：
     * 1. 命中 chunk 取 ±expandWindow 邻块（复用 building 层的 chunk_index/parent_section）
     * 2. 按 chunk_hash 去重
     * 3. 按 parent_section 分组、组内按 chunk_index 升序
     * 4. includeSourceHeading 时每块前加 {@code ## {source_heading}} 标记行
     * 5. 组间用 {@code \n\n---\n\n} 分隔，总长不超 maxContextChars（末尾截断）
     */
    private String buildContext(List<Document> docs) {
        if (docs == null || docs.isEmpty()) {
            return "";
        }
        var ctxCfg = queryProperties.getContext();
        // 1. 邻居扩展
        List<String> hitIds = docs.stream()
            .map(Document::getId)
            .filter(java.util.Objects::nonNull)
            .toList();
        List<Document> expanded = new java.util.ArrayList<>(docs);
        if (!hitIds.isEmpty() && ctxCfg.getExpandWindow() > 0) {
            List<Document> neighbors = vectorService.findNeighborChunks(hitIds, ctxCfg.getExpandWindow());
            expanded.addAll(neighbors);
        }
        // 2. 去重
        List<Document> deduped = dedupByChunkHash(expanded);

        // 3. 按 parent_section 分组
        java.util.Map<String, List<Document>> bySection = new java.util.LinkedHashMap<>();
        for (Document d : deduped) {
            Object ps = d.getMetadata().get("parent_section");
            String section = ps != null ? ps.toString() : "";
            bySection.computeIfAbsent(section, k -> new java.util.ArrayList<>()).add(d);
        }

        // 4/5. 组内按 chunk_index 排序，拼装，加 heading 标记，截断
        StringBuilder sb = new StringBuilder();
        boolean firstSection = true;
        for (var entry : bySection.entrySet()) {
            List<Document> sectionDocs = entry.getValue().stream()
                .sorted(java.util.Comparator.comparingInt(this::chunkIndex))
                .toList();
            for (Document d : sectionDocs) {
                String block = d.getText();
                if (ctxCfg.isIncludeSourceHeading()) {
                    Object sh = d.getMetadata().get("source_heading");
                    if (sh != null && !sh.toString().isBlank()) {
                        block = "## " + sh + "\n" + block;
                    }
                }
                if (sb.length() + block.length() > ctxCfg.getMaxContextChars()) {
                    break;
                }
                if (!firstSection) {
                    sb.append("\n\n---\n\n");
                }
                sb.append(block);
                firstSection = false;
            }
        }
        return sb.toString();
    }

    private int chunkIndex(Document d) {
        Object idx = d.getMetadata().get("chunk_index");
        if (idx instanceof Number n) {
            return n.intValue();
        }
        if (idx != null) {
            try {
                return Integer.parseInt(idx.toString());
            } catch (NumberFormatException e) {
                return Integer.MAX_VALUE;
            }
        }
        return Integer.MAX_VALUE;
    }

    /**
     * 查询知识库并返回完整响应
     */
    public QueryResponse queryKnowledgeBase(QueryRequest request) {
        String answer = answerQuestion(request.knowledgeBaseIds(), request.question());

        // 获取知识库名称（多个知识库用逗号分隔）
        List<String> kbNames = listService.getKnowledgeBaseNames(request.knowledgeBaseIds());
        String kbNamesStr = String.join("、", kbNames);

        // 使用第一个知识库ID作为主要标识（兼容前端）
        Long primaryKbId = request.knowledgeBaseIds().getFirst();

        return new QueryResponse(answer, primaryKbId, kbNamesStr);
    }

    /**
     * 流式查询知识库（SSE，无上下文）
     *
     * @param knowledgeBaseIds 知识库ID列表
     * @param question 用户问题
     * @return 流式响应
     */
    public Flux<String> answerQuestionStream(List<Long> knowledgeBaseIds, String question) {
        return answerQuestionStream(knowledgeBaseIds, question, List.of());
    }

    /**
     * 流式查询知识库（SSE，支持多轮上下文）
     *
     * @param knowledgeBaseIds 知识库ID列表
     * @param question 用户问题
     * @param history 历史对话消息（可选）
     * @return 流式响应
     */
    public Flux<String> answerQuestionStream(List<Long> knowledgeBaseIds, String question, List<Message> history) {
        log.info("收到知识库流式提问: kbIds={}, question={}, historySize={}", knowledgeBaseIds, question,
                history != null ? history.size() : 0);
        if (knowledgeBaseIds == null || knowledgeBaseIds.isEmpty() || normalizeQuestion(question).isBlank()) {
            return Flux.just(NO_RESULT_RESPONSE);
        }

        try {
            // 1. 验证知识库是否存在并更新问题计数
            countService.updateQuestionCounts(knowledgeBaseIds);

            // 2. Query rewrite + 动态参数检索
            List<Message> effectiveHistory = sanitizeHistory(history);
            QueryContext queryContext = buildQueryContext(question, effectiveHistory);
            List<Document> relevantDocs = retrieveRelevantDocs(queryContext, knowledgeBaseIds);

            if (!hasEffectiveHit(relevantDocs)) {
                return Flux.just(NO_RESULT_RESPONSE);
            }

            // 3. 构建上下文（含邻居扩展 + section-aware 装配）
            String context = buildContext(relevantDocs);

            log.debug("检索到 {} 个相关文档片段", relevantDocs.size());

            // 4. 构建提示词
            String systemPrompt = buildSystemPrompt();
            String userPrompt = buildUserPrompt(context, question);

            // 5. 流式调用（带历史上下文）+ 探测窗口归一化
            var promptSpec = getChatClient().prompt().system(systemPrompt);
            if (!effectiveHistory.isEmpty()) {
                promptSpec = promptSpec.messages(effectiveHistory);
            }
            Flux<String> responseFlux = promptSpec
                    .user(userPrompt)
                    .stream()
                    .content();

            log.info("开始流式输出知识库回答(探测窗口): kbIds={}", knowledgeBaseIds);
            return normalizeStreamOutput(responseFlux)
                .doOnComplete(() -> log.info("流式输出完成: kbIds={}", knowledgeBaseIds))
                .onErrorResume(e -> {
                    log.error("流式输出失败: kbIds={}, error={}", knowledgeBaseIds, e.getMessage(), e);
                    return Flux.just("【错误】知识库查询失败：AI服务暂时不可用，请稍后重试。");
                });

        } catch (Exception e) {
            log.error("知识库流式问答失败: {}", e.getMessage(), e);
            return Flux.just("【错误】知识库查询失败：" + e.getMessage());
        }
    }

    private QueryContext buildQueryContext(String originalQuestion, List<Message> history) {
        String normalizedQuestion = normalizeQuestion(originalQuestion);
        Set<String> candidates = new LinkedHashSet<>();
        var mqCfg = queryProperties.getMultiQuery();
        if (mqCfg.isEnabled()) {
            // 多 query 改写：LLM 产出 N 个变体（每行一个），全部作为候选
            candidates.addAll(rewriteMultiQuery(normalizedQuestion, history, mqCfg.getVariantCount()));
        } else {
            candidates.add(rewriteQuestion(normalizedQuestion, history));
        }
        candidates.add(normalizedQuestion);

        SearchParams searchParams = resolveSearchParams(normalizedQuestion);
        return new QueryContext(normalizedQuestion, new ArrayList<>(candidates), searchParams);
    }

    private List<Message> sanitizeHistory(List<Message> history) {
        if (history == null || history.isEmpty()) {
            return List.of();
        }
        // 尊重 history.enabled 配置；并防御直调 QueryService 时历史无界（第二道闸，
        // RagChatSessionService.loadHistoryMessages 已按 maxMessages 截断）
        if (!queryProperties.getHistory().isEnabled()) {
            return List.of();
        }
        int max = queryProperties.getHistory().getMaxMessages();
        if (history.size() > max) {
            return history.subList(history.size() - max, history.size());
        }
        return history;
    }

//       清洗
    private String normalizeQuestion(String question) {
        return question == null ? "" : question.trim();
    }

//    向量检索
    private List<Document> retrieveRelevantDocs(QueryContext queryContext, List<Long> knowledgeBaseIds) {
        var hybridCfg = queryProperties.getHybrid();
        if (hybridCfg.isEnabled()) {
            return retrieveHybrid(queryContext, knowledgeBaseIds);
        }
        var rerankCfg = queryProperties.getRerank();
        if (rerankCfg.isEnabled()) {
            return retrieveRerank(queryContext, knowledgeBaseIds);
        }
        return retrieveDenseFirstHit(queryContext, knowledgeBaseIds);
    }

    /** hybrid：dense 过召回 + BM25 关键词召回 并行 → RRF 融合 →（可选）rerank 精排 */
    private List<Document> retrieveHybrid(QueryContext queryContext, List<Long> knowledgeBaseIds) {
        var hybridCfg = queryProperties.getHybrid();
        var rerankCfg = queryProperties.getRerank();
        // dense 路召回参数：rerank 也开启时用 rerank.candidateTopK，否则用 resolveSearchParams
        int denseTopK = rerankCfg.isEnabled() ? rerankCfg.getCandidateTopK()
            : queryContext.searchParams().topK();
        double denseMinScore = rerankCfg.isEnabled() ? rerankCfg.getMinScore()
            : queryContext.searchParams().minScore();

        List<Document> dense = new java.util.ArrayList<>();
        for (String q : queryContext.candidateQueries()) {
            if (q.isBlank()) continue;
            dense.addAll(vectorService.similaritySearch(q, knowledgeBaseIds, denseTopK, denseMinScore));
        }
        List<Document> keyword = keywordSearchRepository.searchByBm25(
            queryContext.originalQuestion(), knowledgeBaseIds, hybridCfg.getKeywordTopK());

        List<Document> fused = rrfFuse(dense, keyword, hybridCfg.getRrfK());
        log.info("hybrid 融合: dense={}, keyword={}, 融合后={}", dense.size(), keyword.size(), fused.size());
        if (fused.isEmpty()) {
            return List.of();
        }
        if (!rerankCfg.isEnabled()) {
            int limit = rerankCfg.getTopN();
            return fused.stream().limit(limit).toList();
        }
        return applyRerank(queryContext.originalQuestion(), fused, rerankCfg);
    }

    /** 两阶段：dense 过召回 → 去重 → rerank 精排 → topN */
    private List<Document> retrieveRerank(QueryContext queryContext, List<Long> knowledgeBaseIds) {
        var rerankCfg = queryProperties.getRerank();
        List<Document> all = new java.util.ArrayList<>();
        for (String candidateQuery : queryContext.candidateQueries()) {
            if (candidateQuery.isBlank()) {
                continue;
            }
            List<Document> docs = vectorService.similaritySearch(
                candidateQuery, knowledgeBaseIds,
                rerankCfg.getCandidateTopK(), rerankCfg.getMinScore());
            log.info("rerank 过召回候选 query='{}'，命中 {} 条", candidateQuery, docs.size());
            all.addAll(docs);
        }
        List<Document> deduped = dedupByChunkHash(all);
        if (deduped.isEmpty()) {
            return List.of();
        }
        return applyRerank(queryContext.originalQuestion(), deduped, rerankCfg);
    }

    /** 对候选集调用 RerankService 精排；失败降级取原序前 topN */
    private List<Document> applyRerank(String originalQuestion, List<Document> candidates,
                                       KnowledgeBaseQueryProperties.Rerank rerankCfg) {
        RerankService rerankService = llmProviderRegistry.getRerankService();
        if (rerankService == null) {
            log.warn("rerank 已开启但无可用 rerank provider，降级到 dense 顺序取 topN");
            return candidates.stream().limit(rerankCfg.getTopN()).toList();
        }
        List<String> docTexts = candidates.stream().map(Document::getText).toList();
        var results = rerankService.rerank(originalQuestion, docTexts, rerankCfg.getTopN(), rerankCfg.getModel());
        if (results == null) {
            return candidates.stream().limit(rerankCfg.getTopN()).toList();
        }
        List<Document> ranked = results.stream()
            .filter(r -> r.index() != null && r.index() >= 0 && r.index() < candidates.size())
            .filter(r -> r.score() == null || r.score() >= rerankCfg.getMinScore())
            .map(r -> candidates.get(r.index()))
            .toList();
        log.info("rerank 精排完成: 候选 {} → 返回 {}", candidates.size(), ranked.size());
        return ranked;
    }

    /**
     * RRF（Reciprocal Rank Fusion）融合两路结果。
     * score = Σ 1/(rrfK + rank)，rank 从 1 起。按 fused score 降序。
     */
    private List<Document> rrfFuse(List<Document> dense, List<Document> keyword, int rrfK) {
        java.util.Map<String, Document> docs = new java.util.LinkedHashMap<>();
        java.util.Map<String, Double> scores = new java.util.HashMap<>();
        rrfAccumulate(dense, rrfK, docs, scores);
        rrfAccumulate(keyword, rrfK, docs, scores);
        return docs.values().stream()
            .sorted((a, b) -> {
                String ka = chunkKey(a), kb = chunkKey(b);
                return Double.compare(scores.getOrDefault(kb, 0.0), scores.getOrDefault(ka, 0.0));
            })
            .toList();
    }

    private void rrfAccumulate(List<Document> docs, int rrfK,
                               java.util.Map<String, Document> out,
                               java.util.Map<String, Double> scores) {
        int rank = 1;
        for (Document d : docs) {
            String key = chunkKey(d);
            out.putIfAbsent(key, d);
            scores.merge(key, 1.0 / (rrfK + rank), Double::sum);
            rank++;
        }
    }

    private String chunkKey(Document d) {
        Object hash = d.getMetadata().get("chunk_hash");
        return hash != null ? hash.toString() : String.valueOf(d.getId());
    }

    /** 未开启 rerank 的兜底：首个非空候选即返回 */
    private List<Document> retrieveDenseFirstHit(QueryContext queryContext, List<Long> knowledgeBaseIds) {
        for (String candidateQuery : queryContext.candidateQueries()) {
            if (candidateQuery.isBlank()) {
                continue;
            }
            List<Document> docs = vectorService.similaritySearch(
                candidateQuery,
                knowledgeBaseIds,
                queryContext.searchParams().topK(),
                queryContext.searchParams().minScore()
            );
            log.info("检索候选 query='{}'，命中 {} 条", candidateQuery, docs.size());
            if (hasEffectiveHit(docs)) {
                return docs;
            }
        }
        return List.of();
    }

    /** 按 chunk_hash 去重（无 hash 的老数据按 Document.id 去重），保留首次出现顺序 */
    private List<Document> dedupByChunkHash(List<Document> docs) {
        java.util.Map<String, Document> deduped = new java.util.LinkedHashMap<>();
        for (Document d : docs) {
            deduped.putIfAbsent(chunkKey(d), d);
        }
        return new java.util.ArrayList<>(deduped.values());
    }

    private SearchParams resolveSearchParams(String question) {
        var rerankCfg = queryProperties.getRerank();
        if (rerankCfg.isEnabled()) {
            return new SearchParams(rerankCfg.getCandidateTopK(), rerankCfg.getMinScore());
        }
        int compactLength = question.replaceAll("\\s+", "").length();
        if (compactLength <= shortQueryLength) {
            return new SearchParams(topkShort, minScoreShort);
        }
        if (compactLength <= queryProperties.getSearch().getMediumQueryLength()) {
            return new SearchParams(topkMedium, minScoreDefault);
        }
        return new SearchParams(topkLong, minScoreDefault);
    }

//    改写
    private String rewriteQuestion(String question, List<Message> history) {
        if (!rewriteEnabled || question.isBlank()) {
            return question;
        }
        try {
            Map<String, Object> variables = new HashMap<>();
            variables.put("question", question);
            variables.put("history", formatHistoryForRewrite(history));
            variables.put("multi_query_instruction", "6. 只输出一行改写后的查询");
            String rewritePrompt = rewritePromptTemplate.render(variables);
            String rewritten = getChatClient().prompt()
                .user(rewritePrompt)
                .call()
                .content();
            if (rewritten == null || rewritten.isBlank()) {
                return question;
            }
            String normalized = rewritten.trim();
            log.info("Query rewrite: origin='{}', rewritten='{}', historySize={}", question, normalized, history.size());
            return normalized;
        } catch (Exception e) {
            log.warn("Query rewrite 失败，使用原问题继续检索: {}", e.getMessage());
            return question;
        }
    }

    /**
     * 多 query 改写：让 LLM 产出 N 个语义等价但表述不同的检索变体（每行一个）。
     * 失败时回退到单 query 改写结果。
     */
    private List<String> rewriteMultiQuery(String question, List<Message> history, int variantCount) {
        if (!rewriteEnabled || question.isBlank()) {
            return List.of(question);
        }
        int n = Math.max(variantCount, 1);
        try {
            Map<String, Object> variables = new HashMap<>();
            variables.put("question", question);
            variables.put("history", formatHistoryForRewrite(history));
            variables.put("multi_query_instruction",
                "6. 输出 " + n + " 个语义等价但表述角度不同的检索查询，每行一个，不要编号、不要空行");
            String rewritePrompt = rewritePromptTemplate.render(variables);
            String raw = getChatClient().prompt().user(rewritePrompt).call().content();
            if (raw == null || raw.isBlank()) {
                return List.of(question);
            }
            List<String> variants = raw.lines()
                .map(String::trim)
                .filter(s -> !s.isBlank())
                .limit(n)
                .toList();
            if (variants.isEmpty()) {
                return List.of(question);
            }
            log.info("Multi-query rewrite: origin='{}', variants={}", question, variants);
            return variants;
        } catch (Exception e) {
            log.warn("Multi-query rewrite 失败，回退单 query: {}", e.getMessage());
            return List.of(rewriteQuestion(question, history));
        }
    }

    /**
     * 将历史消息格式化为重写 prompt 中的文本摘要。
     * 每条消息格式：用户: xxx / 助手: xxx
     */
    private String formatHistoryForRewrite(List<Message> history) {
        if (history == null || history.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (Message msg : history) {
            if (msg instanceof UserMessage) {
                sb.append("用户: ").append(msg.getText()).append("\n");
            } else if (msg instanceof AssistantMessage) {
                // 截断过长的助手回复，避免 rewrite prompt 过长
                String text = msg.getText();
                if (text.length() > MAX_REWRITE_HISTORY_CHAR) {
                    text = text.substring(0, MAX_REWRITE_HISTORY_CHAR) + "...";
                }
                sb.append("助手: ").append(text).append("\n");
            }
        }
        return sb.toString().trim();
    }

    private boolean hasEffectiveHit(List<Document> docs) {
        return docs != null && !docs.isEmpty();
    }

    private String normalizeAnswer(String answer) {
        if (answer == null || answer.isBlank()) {
            return NO_RESULT_RESPONSE;
        }
        String normalized = answer.trim();
        if (isNoResultLike(normalized)) {
            return NO_RESULT_RESPONSE;
        }
        return normalized;
    }

    private boolean isNoResultLike(String text) {
        return text.contains("没有找到相关信息")
            || text.contains("未检索到相关信息")
            || text.contains("信息不足")
            || text.contains("超出知识库范围")
            || text.contains("无法根据提供内容回答");
    }

    /**
     * 先观察前一小段流式内容，快速识别“无信息”模板。
     * - 命中无信息：立即输出固定模板并结束，防止长篇拒答
     * - 非无信息：尽快释放缓冲并继续实时透传
     */
    private Flux<String> normalizeStreamOutput(Flux<String> rawFlux) {
        return Flux.create(sink -> {
            StringBuilder probeBuffer = new StringBuilder();
            AtomicBoolean passthrough = new AtomicBoolean(false);
            AtomicBoolean completed = new AtomicBoolean(false);
            final Disposable[] disposableRef = new Disposable[1];

            disposableRef[0] = rawFlux.subscribe(
                chunk -> {
                    if (completed.get() || sink.isCancelled()) {
                        return;
                    }
                    if (passthrough.get()) {
                        sink.next(chunk);
                        return;
                    }

                    probeBuffer.append(chunk);
                    String probeText = probeBuffer.toString();
                    if (isNoResultLike(probeText)) {
                        completed.set(true);
                        sink.next(NO_RESULT_RESPONSE);
                        sink.complete();
                        if (disposableRef[0] != null) {
                            disposableRef[0].dispose();
                        }
                        return;
                    }

                    if (probeBuffer.length() >= STREAM_PROBE_CHARS) {
                        passthrough.set(true);
                        sink.next(probeText);
                        probeBuffer.setLength(0);
                    }
                },
                sink::error,
                () -> {
                    if (completed.get() || sink.isCancelled()) {
                        return;
                    }
                    if (!passthrough.get()) {
                        sink.next(normalizeAnswer(probeBuffer.toString()));
                    }
                    sink.complete();
                }
            );

            sink.onCancel(() -> {
                if (disposableRef[0] != null) {
                    disposableRef[0].dispose();
                }
            });
        });
    }

    private record SearchParams(int topK, double minScore) {
    }

    private record QueryContext(String originalQuestion, List<String> candidateQueries, SearchParams searchParams) {
    }
}
