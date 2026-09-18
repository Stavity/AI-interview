package interview.guide.modules.knowledgebase.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link KnowledgeBaseChunkService} 单元测试。
 * 覆盖：标题解析、中文标点切句、贪心打包、overlap、metadata 字段、min-length 过滤、空输入、无标题平文、SHA-256 确定性。
 */
@DisplayName("知识库分块服务测试")
class KnowledgeBaseChunkServiceTest {

    private KnowledgeBaseChunkService chunkService;

    @BeforeEach
    void setUp() {
        chunkService = new KnowledgeBaseChunkService(new KnowledgeBaseChunkProperties());
    }

    /** 小参数实例：target=80, overlap=20, min=1，便于隔离切分行为 */
    private KnowledgeBaseChunkService smallChunker() {
        KnowledgeBaseChunkProperties p = new KnowledgeBaseChunkProperties();
        p.setTargetChunkChars(80);
        p.setOverlapChars(20);
        p.setMinChunkChars(1);
        return new KnowledgeBaseChunkService(p);
    }

    @Nested
    @DisplayName("边界与空输入")
    class EdgeCases {
        @Test
        @DisplayName("null 输入返回空列表")
        void nullReturnsEmpty() {
            assertTrue(chunkService.chunk(null).isEmpty());
        }

        @Test
        @DisplayName("空白输入返回空列表")
        void blankReturnsEmpty() {
            assertTrue(chunkService.chunk("   \n  \t ").isEmpty());
        }
    }

    @Nested
    @DisplayName("markdown 标题与章节")
    class HeadingTests {
        @Test
        @DisplayName("标题层级写入 parent_section，用 > 连接")
        void parentSectionJoinsHeadings() {
            chunkService = smallChunker();
            String content = "# 第一章\n## 1.1 基础\n这是基础内容，描述了核心概念。\n";
            List<Document> docs = chunkService.chunk(content);
            assertFalse(docs.isEmpty());
            // 找到含正文的 chunk（source_heading 为 1.1 基础）
            Document bodyChunk = docs.stream()
                .filter(d -> "1.1 基础".equals(d.getMetadata().get("source_heading")))
                .findFirst()
                .orElseThrow(() -> new AssertionError("应存在 source_heading=1.1 基础 的 chunk"));
            assertEquals("第一章 > 1.1 基础", bodyChunk.getMetadata().get("parent_section"));
        }

        @Test
        @DisplayName("同级新标题替换上一个同级标题")
        void siblingHeadingReplaces() {
            chunkService = smallChunker();
            String content = "# A\n内容一很长用于通过最小长度过滤检查。\n# B\n内容二很长用于通过最小长度过滤检查。\n";
            List<Document> docs = chunkService.chunk(content);
            assertTrue(docs.stream().anyMatch(d -> "B".equals(d.getMetadata().get("source_heading"))));
            assertTrue(docs.stream().anyMatch(d -> "A".equals(d.getMetadata().get("source_heading"))));
            // B 的 parent_section 不应再包含 A
            assertTrue(docs.stream().anyMatch(d -> "B".equals(d.getMetadata().get("parent_section"))));
        }

        @Test
        @DisplayName("无标题平文：parent_section 为空字符串，仍能切分")
        void flatTextNoHeading() {
            chunkService = smallChunker();
            String content = "这是一段没有标题的平文内容。第二句话继续说明。第三句话补充细节。\n";
            List<Document> docs = chunkService.chunk(content);
            assertFalse(docs.isEmpty());
            assertEquals("", docs.get(0).getMetadata().get("parent_section"));
        }
    }

    @Nested
    @DisplayName("切句与打包")
    class SplitAndPack {
        @Test
        @DisplayName("按中文标点 。？！ 切句")
        void splitsOnChinesePunctuation() {
            chunkService = smallChunker();
            String content = "第一句内容较长。第二句继续说明？第三句补充细节！第四句收尾。\n";
            List<Document> docs = chunkService.chunk(content);
            assertFalse(docs.isEmpty());
            String all = docs.stream().map(Document::getText).reduce("", (a, b) -> a + b);
            assertTrue(all.contains("第一句"));
            assertTrue(all.contains("第二句"));
            assertTrue(all.contains("第三句"));
        }

        @Test
        @DisplayName("超长无标点句被硬切，每片不超过 targetChunkChars")
        void oversizedSentenceHardSplit() {
            KnowledgeBaseChunkProperties props = new KnowledgeBaseChunkProperties();
            props.setTargetChunkChars(100);
            props.setOverlapChars(0);
            props.setMinChunkChars(1);
            chunkService = new KnowledgeBaseChunkService(props);

            String noPunct = "x".repeat(350);
            List<Document> docs = chunkService.chunk(noPunct);
            assertFalse(docs.isEmpty());
            for (Document d : docs) {
                assertTrue(d.getText().length() <= 100,
                    "硬切片不应超过 targetChunkChars，实际: " + d.getText().length());
            }
        }

        @Test
        @DisplayName("相邻 chunk 存在 overlap（首块除外）")
        void overlapBetweenChunks() {
            chunkService = smallChunker();
            // 每句 ~22 字符，30 句 ~660 字符，target=80 → 多块
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 30; i++) {
                sb.append("这是第").append(i).append("个句子，内容较长用于触发多块切分。");
            }
            List<Document> docs = chunkService.chunk(sb.toString());
            assertTrue(docs.size() >= 2, "应产生至少 2 个 chunk，实际: " + docs.size());
            // 第二块开头应与第一块末尾有重叠字符
            int overlap = 20;
            String firstText = docs.get(0).getText();
            String secondText = docs.get(1).getText();
            String firstTail = firstText.substring(Math.max(0, firstText.length() - overlap));
            String secondHead = secondText.substring(0, Math.min(secondText.length(), overlap));
            // overlap 意味着第二块开头的内容片段出现在第一块尾部
            boolean overlapped = false;
            for (int len = overlap; len >= 2; len--) {
                if (firstTail.contains(secondHead.substring(0, len))
                    || firstText.endsWith(secondHead.substring(0, len))) {
                    overlapped = true;
                    break;
                }
            }
            assertTrue(overlapped, "第二 chunk 开头应与第一 chunk 末尾重叠");
        }
    }

    @Nested
    @DisplayName("metadata 完整性")
    class MetadataTests {
        @Test
        @DisplayName("每个 chunk 都有 chunk_index / total_chunks / parent_section / source_heading / chunk_hash")
        void allMetadataFieldsPresent() {
            chunkService = smallChunker();
            String content = "# 标题\n句子一内容较长。句子二内容较长。句子三内容较长。\n";
            List<Document> docs = chunkService.chunk(content);
            assertFalse(docs.isEmpty());
            for (int i = 0; i < docs.size(); i++) {
                Document d = docs.get(i);
                assertEquals(i, d.getMetadata().get("chunk_index"), "chunk_index 应为顺序值");
                assertEquals(docs.size(), d.getMetadata().get("total_chunks"));
                assertNotNull(d.getMetadata().get("parent_section"));
                assertNotNull(d.getMetadata().get("source_heading"));
                assertNotNull(d.getMetadata().get("chunk_hash"));
                assertTrue(((String) d.getMetadata().get("chunk_hash")).length() > 0);
            }
        }

        @Test
        @DisplayName("chunk_index 单调递增从 0 开始")
        void chunkIndexMonotonic() {
            StringBuilder sb = new StringBuilder("# H\n");
            for (int i = 0; i < 40; i++) {
                sb.append("句子").append(i).append("，内容充实。");
            }
            List<Document> docs = chunkService.chunk(sb.toString());
            for (int i = 0; i < docs.size(); i++) {
                final int expected = i;
                assertEquals(expected, docs.get(i).getMetadata().get("chunk_index"),
                    "chunk_index 在位置 " + i + " 处不匹配");
            }
        }

        @Test
        @DisplayName("相同输入产生相同 chunk_hash（确定性）")
        void hashDeterministic() {
            String content = "相同内容应产生相同哈希。第二句话。\n";
            List<Document> a = chunkService.chunk(content);
            List<Document> b = chunkService.chunk(content);
            assertEquals(a.size(), b.size());
            for (int i = 0; i < a.size(); i++) {
                assertEquals(a.get(i).getMetadata().get("chunk_hash"),
                    b.get(i).getMetadata().get("chunk_hash"),
                    "相同输入的 chunk_hash 应一致");
            }
        }
    }

    @Nested
    @DisplayName("min-length 过滤")
    class MinLengthTests {
        @Test
        @DisplayName("短于 minChunkChars 的 chunk 被丢弃")
        void shortChunksDropped() {
            KnowledgeBaseChunkProperties props = new KnowledgeBaseChunkProperties();
            props.setTargetChunkChars(500);
            props.setMinChunkChars(50);
            chunkService = new KnowledgeBaseChunkService(props);
            // 单句很短
            List<Document> docs = chunkService.chunk("短。");
            assertTrue(docs.isEmpty(), "过短的 chunk 应被过滤");
        }
    }
}
