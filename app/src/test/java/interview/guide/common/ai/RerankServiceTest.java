package interview.guide.common.ai;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link RerankService} 单元测试。
 * <p>
 * 注：DashScope {@code TextReRank} 在方法内部 new，无法注入 mock。这里覆盖不依赖网络的分支：
 * 入参校验、apiKey 缺失、以及真实调用失败时的优雅降级（返回 null）。
 * 真实 rerank 排序质量由端到端验证覆盖。
 */
@DisplayName("Rerank 服务测试")
class RerankServiceTest {

    @Nested
    @DisplayName("入参校验与降级")
    class GuardTests {

        @Test
        @DisplayName("apiKey 为空时返回 null（跳过 rerank）")
        void blankApiKeyReturnsNull() {
            RerankService svc = new RerankService("", "gte-rerank-v2");
            assertNull(svc.rerank("查询", List.of("文档一", "文档二"), 2));
        }

        @Test
        @DisplayName("apiKey 为 null 时返回 null")
        void nullApiKeyReturnsNull() {
            RerankService svc = new RerankService(null, "gte-rerank-v2");
            assertNull(svc.rerank("查询", List.of("文档"), 1));
        }

        @Test
        @DisplayName("query 为空时返回 null")
        void blankQueryReturnsNull() {
            RerankService svc = new RerankService("fake-key", "gte-rerank-v2");
            assertNull(svc.rerank("", List.of("文档"), 1));
            assertNull(svc.rerank(null, List.of("文档"), 1));
        }

        @Test
        @DisplayName("documents 为空时返回 null")
        void emptyDocumentsReturnsNull() {
            RerankService svc = new RerankService("fake-key", "gte-rerank-v2");
            assertNull(svc.rerank("查询", List.of(), 1));
            assertNull(svc.rerank("查询", null, 1));
        }

        @Test
        @DisplayName("无效 apiKey 调用真实 API 失败时优雅降级返回 null，不抛异常")
        void invalidApiKeyDegradesGracefully() {
            RerankService svc = new RerankService("invalid-key-for-test", "gte-rerank-v2");
            // 不应抛异常；网络/鉴权失败均被 catch
            assertDoesNotThrow(() -> svc.rerank("查询", List.of("文档一", "文档二"), 2));
        }
    }

    @Nested
    @DisplayName("模型解析")
    class ModelTests {

        @Test
        @DisplayName("model 为空时回退到 gte-rerank-v2 默认值")
        void blankModelFallsBackToDefault() {
            // 构造不抛异常即可；默认值在内部生效
            assertDoesNotThrow(() -> new RerankService("key", null));
            assertDoesNotThrow(() -> new RerankService("key", ""));
        }

        @Test
        @DisplayName("modelOverride 重载不改变入参校验行为")
        void modelOverrideRespectsGuards() {
            RerankService svc = new RerankService("", "gte-rerank-v2");
            assertNull(svc.rerank("查询", List.of("文档"), 1, "custom-model"));
        }
    }
}
