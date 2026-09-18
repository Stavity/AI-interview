package interview.guide.modules.multiround.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 实体列长度 vs Flyway DDL 的离线漂移守卫。
 * <p>
 * 为什么需要它：
 * <ul>
 *   <li>{@code ddl-auto: validate} <b>不校验列长度</b>——Hibernate 的
 *       {@code ColumnDefinitions.hasMatchingType} 会用 {@code stripArgs} 去掉 "(n)"，
 *       {@code hasMatchingLength} 只在 {@code ddl-auto: update} 路径被调用。
 *       所以实体声明 varchar(255) 而 DDL 是 varchar(32) 时 validate 静默放过。</li>
 *   <li>测试用 H2 + {@code create-drop} 且禁用 Flyway，表结构是<b>由实体反推</b>出来的，
 *       长度不匹配在测试里完全隐形，只在生产写入真实数据时才炸
 *       （LLM 生成的 questionType 曾可直接越界 VARCHAR(32)）。</li>
 *   <li>主 schema 含 {@code CREATE EXTENSION vector}，普通 postgres 镜像跑不了完整迁移链，
 *       因此用离线比对而非 Testcontainers 来覆盖这一类缺陷。</li>
 * </ul>
 * 本测试直接解析迁移 SQL，与 JPA 无关，无需数据库、始终运行。
 */
@DisplayName("多轮面试实体与 DDL 列长度一致性")
class MultiRoundSchemaDriftTest {

  private static final Path MIGRATION_DIR = Path.of("src/main/resources/db/migration");

  /** 需要守护的 表名 -> (列名 -> 实体声明长度)，与各实体的 @Column(length=...) 保持同步 */
  private static final Map<String, Map<String, Integer>> EXPECTED = Map.of(
      "multi_round_questions", Map.of(
          "question_type", 32,
          "category_key", 128,
          "status", 32),
      "multi_rounds", Map.of(
          "round_type", 32,
          "status", 32),
      "algorithm_submissions", Map.of(
          "language", 16,
          "template_version", 32,
          "evaluation_status", 16,
          "evaluation_error_code", 64),
      "interview_agent_runs", Map.of(
          "trigger_type", 32,
          "status", 20,
          "input_hash", 64,
          "error_code", 64),
      "multi_round_idempotency", Map.of(
          "session_id", 36,
          "operation_type", 32,
          "idempotency_key", 128,
          "resource_type", 32),
      "multi_round_interviews", Map.of(
          "status", 32,
          "provider_id", 64,
          "provider_fingerprint", 64,
          "resume_snapshot_version", 64,
          "skill_id", 64,
          "difficulty", 16,
          "scope_fingerprint", 64));

  @Test
  @DisplayName("实体 @Column(length) 必须与迁移 DDL 的 VARCHAR(n) 完全一致")
  void entityLengthsMustMatchDdl() throws IOException {
    Map<String, Map<String, Integer>> ddl = parseVarcharLengths();
    List<String> problems = new ArrayList<>();

    EXPECTED.forEach((table, columns) -> {
      Map<String, Integer> actual = ddl.get(table);
      if (actual == null) {
        problems.add("迁移中找不到表 " + table);
        return;
      }
      columns.forEach((column, expectedLength) -> {
        Integer actualLength = actual.get(column);
        if (actualLength == null) {
          problems.add(table + "." + column + " 在迁移中不存在或不是 VARCHAR");
        } else if (!actualLength.equals(expectedLength)) {
          problems.add(table + "." + column + " 实体声明 " + expectedLength
              + " 但 DDL 为 " + actualLength);
        }
      });
    });

    assertThat(problems)
        .withFailMessage("实体与 DDL 列长度漂移（ddl-auto=validate 不会报这类问题）:%n%s",
            String.join("\n", problems))
        .isEmpty();
  }

  /** 从 CREATE TABLE / ALTER TABLE ADD COLUMN 中提取每张表的 VARCHAR 列长度 */
  private Map<String, Map<String, Integer>> parseVarcharLengths() throws IOException {
    Map<String, Map<String, Integer>> result = new LinkedHashMap<>();
    Pattern createTable = Pattern.compile(
        "CREATE\\s+TABLE\\s+(?:IF\\s+NOT\\s+EXISTS\\s+)?(\\w+)\\s*\\(", Pattern.CASE_INSENSITIVE);
    // ALTER TABLE 可一次添加多列（ADD COLUMN a ..., ADD COLUMN b ...），先整段取出再逐个 ADD COLUMN 解析
    Pattern alterTable = Pattern.compile(
        "ALTER\\s+TABLE\\s+(\\w+)\\s+((?:.|\\n)*?);", Pattern.CASE_INSENSITIVE);
    Pattern addColumn = Pattern.compile(
        "ADD\\s+COLUMN\\s+(?:IF\\s+NOT\\s+EXISTS\\s+)?(\\w+)\\s+VARCHAR\\((\\d+)\\)",
        Pattern.CASE_INSENSITIVE);
    Pattern columnDef = Pattern.compile("^\\s*(\\w+)\\s+VARCHAR\\((\\d+)\\)", Pattern.CASE_INSENSITIVE);

    List<Path> files;
    try (Stream<Path> stream = Files.list(MIGRATION_DIR)) {
      files = stream.filter(path -> path.toString().endsWith(".sql")).sorted().toList();
    }
    for (Path file : files) {
      String sql = Files.readString(file, StandardCharsets.UTF_8);

      Matcher alter = alterTable.matcher(sql);
      while (alter.find()) {
        String table = alter.group(1).toLowerCase();
        Matcher add = addColumn.matcher(alter.group(2));
        while (add.find()) {
          result.computeIfAbsent(table, key -> new LinkedHashMap<>())
              .put(add.group(1).toLowerCase(), Integer.parseInt(add.group(2)));
        }
      }

      Matcher create = createTable.matcher(sql);
      while (create.find()) {
        String table = create.group(1).toLowerCase();
        String body = sql.substring(create.end(), findBodyEnd(sql, create.end()));
        for (String line : body.split(",\\s*\\n")) {
          Matcher column = columnDef.matcher(line);
          if (column.find()) {
            result.computeIfAbsent(table, key -> new LinkedHashMap<>())
                .put(column.group(1).toLowerCase(), Integer.parseInt(column.group(2)));
          }
        }
      }
    }
    return result;
  }

  /** 找到 CREATE TABLE 的右括号位置（按括号配平） */
  private int findBodyEnd(String sql, int from) {
    int depth = 1;
    for (int i = from; i < sql.length(); i++) {
      char c = sql.charAt(i);
      if (c == '(') depth++;
      else if (c == ')') {
        depth--;
        if (depth == 0) return i;
      }
    }
    return sql.length();
  }
}
