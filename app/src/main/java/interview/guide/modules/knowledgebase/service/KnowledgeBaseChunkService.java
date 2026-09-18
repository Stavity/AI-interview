package interview.guide.modules.knowledgebase.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 知识库分块服务（建立层）。
 * <p>
 * 取代 Spring AI 的 {@link org.springframework.ai.transformer.splitter.TokenTextSplitter}，
 * 原因：TokenTextSplitter 的默认标点列表只有 ASCII {@code ['.','?','!','\n']}，不含中文 {@code 。？！}，
 * 对中文知识库几乎无法按句切分；且不支持 overlap；其 CL100K_BASE tokenizer 与 Qwen embedding 模型不匹配。
 * <p>
 * 本服务采用字符级切分（与 Qwen tokenizer 解耦），按 markdown 标题结构分段、段内按中英文标点切句、
 * 贪心打包到目标字符数、相邻 chunk 带 overlap，并为每个 chunk 写入语义 metadata
 * （chunk_index / chunk_hash / parent_section / source_heading / total_chunks）。
 * <p>
 * 这些语义字段在 {@code applyPendingMetadata} 写入 kb 管理字段时共存，
 * 且 {@code promoteVectorJob} 的 SQL 只动 kb_id/kb_vector_job_id/kb_target_id，语义字段原样保留至检索期。
 */
@Slf4j
@Service
public class KnowledgeBaseChunkService {

    /** markdown 标题行：# 到 ###### */
    private static final Pattern HEADING = Pattern.compile("^(#{1,6})\\s+(.+?)\\s*$");

    /** 句子结束符：中文 。？！… 与 ASCII .!? 以及换行 */
    private static final Pattern SENTENCE_BOUNDARY = Pattern.compile("[。？！.!?…\\n]");

    private final int targetChunkChars;
    private final int overlapChars;
    private final int minChunkChars;
    private final boolean includeHeadingInChunk;

    public KnowledgeBaseChunkService(KnowledgeBaseChunkProperties props) {
        this.targetChunkChars = Math.max(props.getTargetChunkChars(), 1);
        this.overlapChars = Math.min(Math.max(props.getOverlapChars(), 0), this.targetChunkChars - 1);
        this.minChunkChars = Math.max(props.getMinChunkChars(), 1);
        this.includeHeadingInChunk = props.isIncludeHeadingInChunk();
    }

    /**
     * 将原始文本切分为带语义 metadata 的 Document 列表。
     *
     * @param content 解析后的纯文本（保留 markdown 标题语法，见 {@link interview.guide.infrastructure.file.TextCleaningService}）
     * @return chunk 列表，每个 Document 已挂载 chunk_index/chunk_hash/parent_section/source_heading/total_chunks
     */
    public List<Document> chunk(String content) {
        if (content == null || content.isBlank()) {
            return List.of();
        }

        List<ChunkCandidate> candidates = splitIntoCandidates(content);
        if (candidates.isEmpty()) {
            return List.of();
        }

        // 过滤过短 chunk
        List<ChunkCandidate> filtered = new ArrayList<>();
        for (ChunkCandidate c : candidates) {
            if (c.text.strip().length() >= minChunkChars) {
                filtered.add(c);
            }
        }
        if (filtered.isEmpty()) {
            return List.of();
        }

        // 二趟回填 chunk_index / total_chunks
        int total = filtered.size();
        List<Document> docs = new ArrayList<>(total);
        for (int i = 0; i < total; i++) {
            ChunkCandidate c = filtered.get(i);
            Map<String, Object> meta = new LinkedHashMap<>();
            meta.put("chunk_index", i);
            meta.put("total_chunks", total);
            meta.put("parent_section", c.parentSection);
            meta.put("source_heading", c.sourceHeading);
            meta.put("chunk_hash", hash(c.text));
            docs.add(new Document(c.text, meta));
        }
        log.info("分块完成: {} 个 chunks（候选 {} 个，过滤后 {} 个）", docs.size(), candidates.size(), total);
        return docs;
    }

    // ==================== 切分核心 ====================

    /**
     * 按 markdown 标题切段（section），段内按句子贪心打包 + overlap。
     */
    private List<ChunkCandidate> splitIntoCandidates(String content) {
        List<ChunkCandidate> result = new ArrayList<>();
        // 标题栈：元素为 (level, headingText)
        List<int[]> levelStack = new ArrayList<>(); // 仅存 level，文本单独存
        List<String> headingTexts = new ArrayList<>();
        StringBuilder sectionBuffer = new StringBuilder();
        // 上一 chunk 文本，用于 overlap
        String[] prevChunkText = {""};

        String[] lines = content.split("\n", -1);
        for (String line : lines) {
            Matcher m = HEADING.matcher(line);
            if (m.matches()) {
                // 先把已累积的段冲刷成 chunks
                flushSection(result, sectionBuffer, headingTexts, prevChunkText);
                sectionBuffer.setLength(0);
                // 维护标题栈
                int level = m.group(1).length();
                String heading = m.group(2);
                // 弹出 level >= 当前的
                while (!levelStack.isEmpty() && levelStack.get(levelStack.size() - 1)[0] >= level) {
                    levelStack.remove(levelStack.size() - 1);
                    headingTexts.remove(headingTexts.size() - 1);
                }
                levelStack.add(new int[]{level});
                headingTexts.add(heading);
                // 标题行是否进入 chunk 文本
                if (includeHeadingInChunk) {
                    sectionBuffer.append(line).append('\n');
                }
            } else {
                sectionBuffer.append(line).append('\n');
            }
        }
        // 末段
        flushSection(result, sectionBuffer, headingTexts, prevChunkText);

        return result;
    }

    /**
     * 把一个 section 的文本切成若干 chunk 候选，追加到 result。
     * overlap 跨 chunk 生效，但不跨 section（避免跨章节内容串味）。
     */
    private void flushSection(List<ChunkCandidate> result, StringBuilder sectionBuffer,
                              List<String> headingTexts, String[] prevChunkText) {
        if (sectionBuffer.length() == 0) {
            return;
        }
        String sectionText = sectionBuffer.toString();
        String parentSection = String.join(" > ", headingTexts);
        String sourceHeading = headingTexts.isEmpty() ? "" : headingTexts.get(headingTexts.size() - 1);

        List<String> sentences = splitSentences(sectionText);
        StringBuilder current = new StringBuilder();
        for (String sentence : sentences) {
            if (sentence.isEmpty()) {
                continue;
            }
            // 单句超长：硬切
            if (sentence.length() > targetChunkChars) {
                // 先冲刷已累积内容
                if (current.length() > 0) {
                    emitChunk(result, current.toString(), parentSection, sourceHeading, prevChunkText);
                    current.setLength(0);
                }
                List<String> pieces = chunkBySize(sentence, targetChunkChars);
                for (int i = 0; i < pieces.size(); i++) {
                    // 每个硬切片都作为独立 chunk；首片带 overlap，后续片无 overlap（本身连续）
                    String piece = i == 0 ? overlapPrefix(prevChunkText[0]) + pieces.get(i) : pieces.get(i);
                    emitChunk(result, piece, parentSection, sourceHeading, prevChunkText);
                }
                continue;
            }
            // 超过目标大小：先冲刷
            if (current.length() + sentence.length() > targetChunkChars && current.length() > 0) {
                emitChunk(result, current.toString(), parentSection, sourceHeading, prevChunkText);
                // 下一 chunk 以 overlap 起头
                current.setLength(0);
                current.append(overlapPrefix(prevChunkText[0])).append(sentence);
            } else {
                current.append(sentence);
            }
        }
        if (current.length() > 0) {
            emitChunk(result, current.toString(), parentSection, sourceHeading, prevChunkText);
        }
    }

    /**
     * 发出一个 chunk，并更新 prevChunkText 供下一 chunk 的 overlap。
     */
    private void emitChunk(List<ChunkCandidate> result, String text, String parentSection,
                           String sourceHeading, String[] prevChunkText) {
        String trimmed = text.strip();
        if (trimmed.isEmpty()) {
            return;
        }
        result.add(new ChunkCandidate(trimmed, parentSection, sourceHeading));
        prevChunkText[0] = trimmed;
    }

    /**
     * 取上一 chunk 末尾 overlapChars 字符作为下一 chunk 的前缀。
     */
    private String overlapPrefix(String prevChunk) {
        if (overlapChars <= 0 || prevChunk == null || prevChunk.length() <= 0) {
            return "";
        }
        int start = Math.max(0, prevChunk.length() - overlapChars);
        return prevChunk.substring(start);
    }

    /**
     * 按句子边界切分，保留分隔符于前句末尾。边界符见 {@link #SENTENCE_BOUNDARY}。
     */
    private List<String> splitSentences(String text) {
        List<String> sentences = new ArrayList<>();
        int last = 0;
        Matcher m = SENTENCE_BOUNDARY.matcher(text);
        while (m.find()) {
            int end = m.end(); // 包含边界符
            sentences.add(text.substring(last, end));
            last = end;
        }
        if (last < text.length()) {
            sentences.add(text.substring(last));
        }
        return sentences;
    }

    /**
     * 按固定字符数硬切（用于无标点的超长句）。
     */
    private List<String> chunkBySize(String text, int size) {
        List<String> pieces = new ArrayList<>();
        for (int i = 0; i < text.length(); i += size) {
            pieces.add(text.substring(i, Math.min(i + size, text.length())));
        }
        return pieces;
    }

    /**
     * SHA-256 归一化文本哈希（hex），用于未来 chunk 级去重 / 增量更新。
     */
    private static String hash(String text) {
        String normalized = text.strip().replaceAll("\\s+", " ");
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(normalized.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception e) {
            // SHA-256 一定存在，兜底返回长度
            return Integer.toHexString(normalized.hashCode());
        }
    }

    /** 中间态：chunk 文本 + 所属章节信息 */
    private static final class ChunkCandidate {
        final String text;
        final String parentSection;
        final String sourceHeading;

        ChunkCandidate(String text, String parentSection, String sourceHeading) {
            this.text = text;
            this.parentSection = parentSection;
            this.sourceHeading = sourceHeading;
        }
    }
}
