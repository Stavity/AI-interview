package interview.guide.modules.questiontag;

import interview.guide.common.result.Result;
import interview.guide.modules.questiontag.dto.QuestionTagNodeDetailResponse;
import interview.guide.modules.questiontag.dto.QuestionTagNodeResponse;
import interview.guide.modules.questiontag.dto.QuestionTagSearchResponse;
import interview.guide.modules.questiontag.dto.QuestionTaxonomyVersionResponse;
import interview.guide.modules.questiontag.service.QuestionTagQueryService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/question-tags")
@RequiredArgsConstructor
public class QuestionTagController {

  private final QuestionTagQueryService queryService;

  @GetMapping("/versions/current")
  public Result<QuestionTaxonomyVersionResponse> currentVersion() {
    return Result.success(queryService.currentVersion());
  }

  @GetMapping("/roots")
  public Result<List<QuestionTagNodeResponse>> roots(
      @RequestParam(required = false) String skillId
  ) {
    return Result.success(queryService.roots(skillId));
  }

  @GetMapping("/search")
  public Result<QuestionTagSearchResponse> search(
      @RequestParam String q,
      @RequestParam(required = false) String skillId,
      @RequestParam(required = false) Boolean assignableOnly,
      @RequestParam(required = false) Integer limit
  ) {
    return Result.success(queryService.search(q, skillId, assignableOnly, limit));
  }

  @GetMapping("/{code}/children")
  public Result<List<QuestionTagNodeResponse>> children(@PathVariable String code) {
    return Result.success(queryService.children(code));
  }

  @GetMapping("/{code}")
  public Result<QuestionTagNodeDetailResponse> detail(@PathVariable String code) {
    return Result.success(queryService.detail(code));
  }
}
