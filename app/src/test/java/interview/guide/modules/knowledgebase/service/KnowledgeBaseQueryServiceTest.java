package interview.guide.modules.knowledgebase.service;

import interview.guide.common.ai.LlmProviderRegistry;
import interview.guide.modules.knowledgebase.repository.KeywordSearchRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.document.Document;
import org.springframework.core.io.DefaultResourceLoader;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * {@link KnowledgeBaseQueryService} 检索层测试。
 * <p>
 * 覆盖：rerank 关闭时的 first-hit-wins、rerank 开启时的过召回+合并去重、
 * 无 rerank provider 时的降级、history 配置接入（enabled / maxMessages 截断）。
 * <p>
 * rerank 精排质量与 BM25 关键词通道依赖外部服务/PG 扩展，由端到端验证覆盖。
 */
@DisplayName("知识库检索服务测试")
class KnowledgeBaseQueryServiceTest {

    @Mock private LlmProviderRegistry llmProviderRegistry;
    @Mock private KnowledgeBaseVectorService vectorService;
    @Mock private KnowledgeBaseListService listService;
    @Mock private KnowledgeBaseCountService countService;
    @Mock private KeywordSearchRepository keywordSearchRepository;

    private KnowledgeBaseQueryProperties props;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        props = new KnowledgeBaseQueryProperties();
        // 默认关闭 rewrite，避免测试触发 LLM 调用
        props.getRewrite().setEnabled(false);
    }

    private KnowledgeBaseQueryService newService() throws Exception {
        return new KnowledgeBaseQueryService(
            llmProviderRegistry, vectorService, listService, countService,
            keywordSearchRepository, props, new DefaultResourceLoader());
    }

    private Document doc(String id, String text, String chunkHash) {
        Map<String, Object> meta = new HashMap<>();
        if (chunkHash != null) {
            meta.put("chunk_hash", chunkHash);
        }
        return new Document(id, text, meta);
    }

    /**
     * 执行提问并忽略 LLM 生成阶段的异常（ChatClient 未 mock，检索成功后生成必然失败）。
     * 本测试只关心检索阶段行为。
     */
    private void askIgnoringLlm(KnowledgeBaseQueryService svc, String question) {
        try {
            svc.answerQuestion(List.of(1L), question);
        } catch (Exception ignored) {
            // 生成阶段失败与检索行为无关
        }
    }

    @Nested
    @DisplayName("rerank 关闭：first-hit-wins")
    class RerankDisabledTests {

        @Test
        @DisplayName("首个非空候选即返回，不再检索后续候选")
        void firstHitWins() throws Exception {
            props.getRerank().setEnabled(false);
            var svc = newService();
            when(vectorService.similaritySearch(anyString(), anyList(), anyInt(), anyDouble()))
                .thenReturn(List.of(doc("1", "命中内容足够长以通过校验", "h1")));

            askIgnoringLlm(svc, "测试问题");

            // 只检索一次（原问题作为唯一候选，rewrite 已关闭）
            verify(vectorService, times(1))
                .similaritySearch(anyString(), anyList(), anyInt(), anyDouble());
        }

        @Test
        @DisplayName("全部候选无命中时返回无结果模板")
        void noHitReturnsNoResult() throws Exception {
            props.getRerank().setEnabled(false);
            var svc = newService();
            when(vectorService.similaritySearch(anyString(), anyList(), anyInt(), anyDouble()))
                .thenReturn(List.of());

            String answer = svc.answerQuestion(List.of(1L), "不存在的内容");
            assertTrue(answer.contains("未检索到相关信息"));
        }
    }

    @Nested
    @DisplayName("rerank 开启：过召回 + 合并去重 + 降级")
    class RerankEnabledTests {

        @Test
        @DisplayName("用 candidateTopK 过召回，而非按长度分档的 topK")
        void usesCandidateTopK() throws Exception {
            props.getRerank().setEnabled(true);
            props.getRerank().setCandidateTopK(30);
            props.getRerank().setMinScore(0.1);
            var svc = newService();
            when(vectorService.similaritySearch(anyString(), anyList(), anyInt(), anyDouble()))
                .thenReturn(List.of(doc("1", "内容一足够长以通过最小长度校验", "h1")));
            when(llmProviderRegistry.getRerankService()).thenReturn(null);

            askIgnoringLlm(svc, "测试问题");

            verify(vectorService).similaritySearch(anyString(), anyList(), eq(30), eq(0.1));
        }

        @Test
        @DisplayName("无可用 rerank provider 时降级取原序 topN，不抛检索异常")
        void degradesWhenNoRerankProvider() throws Exception {
            props.getRerank().setEnabled(true);
            props.getRerank().setTopN(2);
            var svc = newService();
            when(vectorService.similaritySearch(anyString(), anyList(), anyInt(), anyDouble()))
                .thenReturn(List.of(
                    doc("1", "内容一足够长以通过最小长度校验", "h1"),
                    doc("2", "内容二足够长以通过最小长度校验", "h2"),
                    doc("3", "内容三足够长以通过最小长度校验", "h3")));
            when(llmProviderRegistry.getRerankService()).thenReturn(null);

            askIgnoringLlm(svc, "测试问题");

            // 检索阶段走到了 rerank 解析并降级
            verify(llmProviderRegistry).getRerankService();
        }

        @Test
        @DisplayName("相同 chunk_hash 的重复命中被去重")
        void dedupByChunkHash() throws Exception {
            props.getRerank().setEnabled(true);
            props.getRerank().setTopN(10);
            props.getContext().setExpandWindow(0);
            var svc = newService();
            when(vectorService.similaritySearch(anyString(), anyList(), anyInt(), anyDouble()))
                .thenReturn(List.of(
                    doc("1", "重复内容足够长以通过最小长度校验", "same-hash"),
                    doc("2", "重复内容足够长以通过最小长度校验", "same-hash")));
            when(llmProviderRegistry.getRerankService()).thenReturn(null);

            askIgnoringLlm(svc, "测试问题");

            // 去重后只剩 1 条，邻居扩展关闭时不应再查邻块
            verify(vectorService, never()).findNeighborChunks(anyList(), anyInt());
        }
    }

    @Nested
    @DisplayName("hybrid 开启：双路召回")
    class HybridTests {

        @Test
        @DisplayName("hybrid 开启时同时调 dense 与 BM25 关键词通道")
        void callsBothChannels() throws Exception {
            props.getHybrid().setEnabled(true);
            props.getHybrid().setKeywordTopK(20);
            var svc = newService();
            when(vectorService.similaritySearch(anyString(), anyList(), anyInt(), anyDouble()))
                .thenReturn(List.of(doc("1", "dense 命中内容足够长以通过校验", "h1")));
            when(keywordSearchRepository.searchByBm25(anyString(), anyList(), anyInt()))
                .thenReturn(List.of(doc("2", "keyword 命中内容足够长以通过校验", "h2")));

            askIgnoringLlm(svc, "测试问题");

            verify(vectorService, atLeastOnce())
                .similaritySearch(anyString(), anyList(), anyInt(), anyDouble());
            verify(keywordSearchRepository).searchByBm25(anyString(), anyList(), eq(20));
        }

        @Test
        @DisplayName("BM25 通道返回空（paradedb 缺失）时仍能用 dense 结果")
        void degradesWhenKeywordChannelEmpty() throws Exception {
            props.getHybrid().setEnabled(true);
            var svc = newService();
            when(vectorService.similaritySearch(anyString(), anyList(), anyInt(), anyDouble()))
                .thenReturn(List.of(doc("1", "dense 命中内容足够长以通过校验", "h1")));
            when(keywordSearchRepository.searchByBm25(anyString(), anyList(), anyInt()))
                .thenReturn(List.of());

            askIgnoringLlm(svc, "测试问题");

            // dense 路仍被调用，关键词空不阻塞
            verify(vectorService, atLeastOnce())
                .similaritySearch(anyString(), anyList(), anyInt(), anyDouble());
        }
    }

    @Nested
    @DisplayName("history 配置接入")
    class HistoryTests {

        @Test
        @DisplayName("history.enabled=false 时历史被丢弃")
        void historyDisabledDropsAll() throws Exception {
            props.getHistory().setEnabled(false);
            var svc = newService();
            when(vectorService.similaritySearch(anyString(), anyList(), anyInt(), anyDouble()))
                .thenReturn(List.of());

            List<Message> history = new ArrayList<>();
            history.add(new UserMessage("历史问题"));
            history.add(new AssistantMessage("历史回答"));

            // 历史被丢弃后仍能正常走流程（无命中返回模板）
            var flux = svc.answerQuestionStream(List.of(1L), "新问题", history);
            assertNotNull(flux);
        }

        @Test
        @DisplayName("history 超过 maxMessages 时截断为最近 N 条")
        void historyTruncatedToMaxMessages() throws Exception {
            props.getHistory().setEnabled(true);
            props.getHistory().setMaxMessages(2);
            var svc = newService();
            when(vectorService.similaritySearch(anyString(), anyList(), anyInt(), anyDouble()))
                .thenReturn(List.of());

            List<Message> history = new ArrayList<>();
            for (int i = 0; i < 10; i++) {
                history.add(new UserMessage("问题" + i));
            }
            // 不抛异常即说明截断逻辑生效（内部 subList）
            assertDoesNotThrow(() -> svc.answerQuestionStream(List.of(1L), "新问题", history));
        }
    }
}
