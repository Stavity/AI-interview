package interview.guide.modules.knowledgebase.repository;

import interview.guide.common.tenant.TenantContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 关键词召回通道（BM25，基于 paradedb pg_bm25）。
 * <p>
 * 用 {@code @@@} 操作符 + {@code paradedb.score(id)} 排序。paradedb 不可用时 SQL 抛异常，
 * 被 catch 后返回空列表，hybrid 链路据此降级到纯 dense。
 * <p>
 * metadata 已是 jsonb（见 V20260920），用 {@code ->>'kb_id'} 过滤知识库范围。
 */
@Slf4j
@Repository
@RequiredArgsConstructor
public class KeywordSearchRepository {

    private final JdbcTemplate jdbcTemplate;

    /**
     * BM25 关键词召回。
     *
     * @param query 用户原始问题（作为 BM25 查询串）
     * @param kbIds 知识库 ID 过滤范围；null/空表示不限
     * @param topK 返回数
     * @return 命中文档（含 metadata）；paradedb 不可用时返回空列表
     */
    public List<Document> searchByBm25(String query, List<Long> kbIds, int topK) {
        if (query == null || query.isBlank() || topK <= 0 || kbIds == null || kbIds.isEmpty()) {
            return List.of();
        }
        try {
            Long ownerUserId = TenantContext.requireCurrentTenantId();
            // 用 ANY(?) 传 Long[]；Postgres JDBC 需通过 createArrayOf
            return jdbcTemplate.query(
                con -> {
                    var ps = con.prepareStatement(
                        """
                        SELECT id, content, metadata, paradedb.score(id) AS score
                        FROM vector_store
                        WHERE content @@@ ?
                          AND metadata->>'owner_user_id' = ?
                          AND metadata->>'kb_id' = ANY (?)
                        ORDER BY score DESC
                        LIMIT ?
                        """);
                    ps.setString(1, query);
                    ps.setString(2, ownerUserId.toString());
                    String[] kbArr = kbIds.stream().map(String::valueOf).toArray(String[]::new);
                    ps.setArray(3, con.createArrayOf("text", kbArr));
                    ps.setInt(4, topK);
                    return ps;
                },
                (rs, i) -> mapRow(rs));
        } catch (Exception e) {
            log.warn("BM25 关键词召回失败（paradedb 可能未安装），降级返回空: {}", e.getMessage());
            return List.of();
        }
    }

    @SuppressWarnings("unchecked")
    private Document mapRow(ResultSet rs) throws java.sql.SQLException {
        String idStr = rs.getString("id");
        String content = rs.getString("content");
        Object metaObj = rs.getObject("metadata");
        Map<String, Object> metadata = new HashMap<>();
        if (metaObj != null) {
            String metaStr = metaObj.toString();
            try {
                metadata = new com.fasterxml.jackson.databind.ObjectMapper()
                    .readValue(metaStr, new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
            } catch (Exception e) {
                metadata = new HashMap<>();
            }
        }
        // 用行 UUID 构造，使命中块可参与邻居扩展（small-to-big）
        if (idStr != null) {
            return new Document(idStr, content, metadata);
        }
        return new Document(content, metadata);
    }
}
