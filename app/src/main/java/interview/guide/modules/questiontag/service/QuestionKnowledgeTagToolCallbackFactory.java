package interview.guide.modules.questiontag.service;

import interview.guide.common.exception.BusinessException;
import interview.guide.modules.questiontag.service.QuestionKnowledgeValidator.TagSelection;
import interview.guide.modules.questiontag.service.QuestionTagQueryService.GenerationQueryResponse;
import interview.guide.modules.questiontag.service.QuestionTagQueryService.GenerationQueryResult;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/** Builds non-shared, scope-bound read/validation tools for a single generation run. */
@Component
@Slf4j
public class QuestionKnowledgeTagToolCallbackFactory {

  private static final int MAX_OUTPUT_CHARS = 12_000;
  private static final String QUERY_SCHEMA = """
      {"type":"object","properties":{"parentCode":{"type":"string"},"queries":{"type":"array","items":{"type":"string"},"maxItems":10},"assignableOnly":{"type":"boolean"},"limitPerQuery":{"type":"integer","minimum":1,"maximum":20}},"additionalProperties":false}
      """;
  private static final String VALIDATE_SCHEMA = """
      {"type":"object","properties":{"selections":{"type":"array","maxItems":30,"items":{"type":"object","properties":{"clientQuestionId":{"type":"string"},"primaryCode":{"type":"string"},"secondaryCodes":{"type":"array","items":{"type":"string"}}},"required":["clientQuestionId","primaryCode"],"additionalProperties":false}}},"required":["selections"],"additionalProperties":false}
      """;

  private final QuestionTagQueryService queryService;
  private final QuestionKnowledgeValidator validator;
  private final ObjectMapper objectMapper;
  private final MeterRegistry meterRegistry;

  public QuestionKnowledgeTagToolCallbackFactory(
      QuestionTagQueryService queryService,
      QuestionKnowledgeValidator validator,
      ObjectMapper objectMapper,
      @Autowired(required = false) MeterRegistry meterRegistry
  ) {
    this.queryService = queryService;
    this.validator = validator;
    this.objectMapper = objectMapper;
    this.meterRegistry = meterRegistry;
  }

  public List<ToolCallback> create(QuestionGenerationScope scope) {
    AtomicInteger callCount = new AtomicInteger();
    return List.of(
        new QueryTagsCallback(scope, callCount),
        new ValidateTagsCallback(scope, callCount));
  }

  private boolean takeCall(QuestionGenerationScope scope, AtomicInteger callCount, String toolName) {
    int current = callCount.incrementAndGet();
    if (current > scope.maxToolCalls()) {
      log.warn("Question tag tool call limit reached: skill={}, version={}, tool={}",
          scope.skillId(), scope.taxonomyVersion(), toolName);
      return false;
    }
    return true;
  }

  private void recordCall(String tool, String status, long startedAt, boolean noResult, int invalidCount) {
    if (meterRegistry == null) {
      return;
    }
    meterRegistry.counter("app.question_tag_tool.calls", "tool", tool, "status", status).increment();
    meterRegistry.timer("app.question_tag_tool.latency", "tool", tool)
        .record(System.nanoTime() - startedAt, TimeUnit.NANOSECONDS);
    if (noResult) {
      meterRegistry.counter("app.question_tag_tool.no_result", "tool", tool).increment();
    }
    if (invalidCount > 0) {
      meterRegistry.counter("app.question_tag_tool.invalid_selections", "tool", tool)
          .increment(invalidCount);
    }
  }

  private String serialize(Object value) {
    try {
      String json = objectMapper.writeValueAsString(value);
      if (json.length() <= MAX_OUTPUT_CHARS || !(value instanceof GenerationQueryResponse response)) {
        return json.length() <= MAX_OUTPUT_CHARS ? json
            : "{\"error\":\"结果超出长度限制，请缩小查询范围或减少关键词\"}";
      }
      List<GenerationQueryResult> results = new ArrayList<>(response.results());
      while (json.length() > MAX_OUTPUT_CHARS) {
        int lastNonEmpty = -1;
        for (int i = results.size() - 1; i >= 0; i--) {
          if (!results.get(i).nodes().isEmpty()) {
            lastNonEmpty = i;
            break;
          }
        }
        if (lastNonEmpty < 0) {
          return "{\"error\":\"结果超出长度限制，请缩小查询范围或减少关键词\"}";
        }
        GenerationQueryResult current = results.get(lastNonEmpty);
        results.set(lastNonEmpty, new GenerationQueryResult(current.query(),
            current.nodes().subList(0, current.nodes().size() - 1)));
        json = objectMapper.writeValueAsString(new GenerationQueryResponse(response.taxonomyVersion(), results));
      }
      return json;
    } catch (JacksonException e) {
      return "{\"error\":\"标签工具结果序列化失败\"}";
    }
  }

  private abstract class ScopedCallback implements ToolCallback {
    final QuestionGenerationScope scope;
    final AtomicInteger callCount;
    final ToolDefinition definition;

    ScopedCallback(QuestionGenerationScope scope, AtomicInteger callCount,
                   String name, String description, String schema) {
      this.scope = scope;
      this.callCount = callCount;
      this.definition = ToolDefinition.builder().name(name).description(description).inputSchema(schema).build();
    }

    @Override
    public ToolDefinition getToolDefinition() {
      return definition;
    }

    String checkCallLimit() {
      return takeCall(scope, callCount, definition.name())
          ? null : "本次生成的标签工具调用次数已达上限，请基于已返回的目录结果完成出题";
    }
  }

  private final class QueryTagsCallback extends ScopedCallback {
    QueryTagsCallback(QuestionGenerationScope scope, AtomicInteger callCount) {
      super(scope, callCount,
          "query_question_knowledge_tags",
          "在本次出题固定的知识目录版本和 Skill 范围内查询标签。可按父节点查询直接子节点，或按名称、别名、描述关键词搜索。此工具只读，不会创建或修改标签。",
          QUERY_SCHEMA);
    }

    @Override
    public String call(String toolInput) {
      long startedAt = System.nanoTime();
      String limitError = checkCallLimit();
      if (limitError != null) {
        recordCall(definition.name(), "limit", startedAt, false, 0);
        return serialize(new ToolError(limitError));
      }
      try {
        QueryInput input = objectMapper.readValue(toolInput, QueryInput.class);
        if (input == null) {
          return serialize(new ToolError("查询参数不能为空"));
        }
        GenerationQueryResponse result = queryService.queryForGeneration(
            scope.taxonomyVersion(), scope.allowedRootCodes(), input.parentCode(), input.queries(),
            input.assignableOnly(), input.limitPerQuery());
        boolean noResult = result.results().stream().allMatch(query -> query.nodes().isEmpty());
        recordCall(definition.name(), "success", startedAt, noResult, 0);
        return serialize(result);
      } catch (JacksonException | BusinessException e) {
        recordCall(definition.name(), "failure", startedAt, false, 0);
        return serialize(new ToolError(e.getMessage()));
      }
    }
  }

  private final class ValidateTagsCallback extends ScopedCallback {
    ValidateTagsCallback(QuestionGenerationScope scope, AtomicInteger callCount) {
      super(scope, callCount,
          "validate_question_knowledge_tags",
          "辅助校验一批候选题目的主/次知识点是否属于本次固定目录版本、Skill 范围且允许挂载。它是提示工具；最终持久化前后端仍会独立校验。",
          VALIDATE_SCHEMA);
    }

    @Override
    public String call(String toolInput) {
      long startedAt = System.nanoTime();
      String limitError = checkCallLimit();
      if (limitError != null) {
        recordCall(definition.name(), "limit", startedAt, false, 0);
        return serialize(new ToolError(limitError));
      }
      try {
        ValidationInput input = objectMapper.readValue(toolInput, ValidationInput.class);
        if (input == null || input.selections() == null || input.selections().size() > 30) {
          return serialize(new ToolError("每次最多校验 30 道候选题"));
        }
        if (input.selections().stream().anyMatch(java.util.Objects::isNull)) {
          return serialize(new ToolError("候选知识点条目不能为 null"));
        }
        List<ValidationResult> results = input.selections().stream().map(selection -> {
          try {
            validator.validateQuestionGenerationSelection(
                scope.taxonomyVersion(), scope.allowedRootCodes(), selection.primaryCode(),
                selection.secondaryCodes());
            return new ValidationResult(selection.clientQuestionId(), true, List.of(), List.of());
          } catch (BusinessException e) {
            return new ValidationResult(selection.clientQuestionId(), false,
                List.of(e.getMessage()), List.of());
          }
        }).toList();
        int invalidCount = (int) results.stream().filter(result -> !result.valid()).count();
        recordCall(definition.name(), "success", startedAt, false, invalidCount);
        return serialize(new ValidationResponse(scope.taxonomyVersion(), results));
      } catch (JacksonException e) {
        recordCall(definition.name(), "failure", startedAt, false, 0);
        return serialize(new ToolError("标签校验工具参数不是有效 JSON"));
      }
    }
  }

  private record QueryInput(String parentCode, List<String> queries,
                            Boolean assignableOnly, Integer limitPerQuery) {}
  private record SelectionInput(String clientQuestionId, String primaryCode, List<String> secondaryCodes) {}
  private record ValidationInput(List<SelectionInput> selections) {}
  private record ValidationResult(String clientQuestionId, boolean valid,
                                  List<String> errors, List<String> warnings) {}
  private record ValidationResponse(int taxonomyVersion, List<ValidationResult> items) {}
  private record ToolError(String error) {}
}
