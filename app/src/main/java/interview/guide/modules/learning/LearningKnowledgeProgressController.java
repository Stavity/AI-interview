package interview.guide.modules.learning;

import interview.guide.common.result.Result;
import interview.guide.modules.learning.service.LearningKnowledgeProgressService;
import interview.guide.modules.learning.service.LearningKnowledgeProgressService.EvidenceItem;
import interview.guide.modules.learning.service.LearningKnowledgeProgressService.ProgressSnapshot;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/api/learning")
@RequiredArgsConstructor
public class LearningKnowledgeProgressController {

  private final LearningKnowledgeProgressService service;

  @GetMapping("/knowledge-progress")
  public Result<ProgressSnapshot> progress(
      @RequestParam(required = false) Integer taxonomyVersion
  ) {
    return Result.success(service.currentProgress(taxonomyVersion));
  }

  @PostMapping("/knowledge-progress/rebuild")
  public Result<ProgressSnapshot> rebuild(
      @RequestParam(required = false) Integer taxonomyVersion
  ) {
    return Result.success(service.rebuildCurrentUser(taxonomyVersion));
  }

  @GetMapping("/evidence")
  public Result<List<EvidenceItem>> evidence(
      @RequestParam(required = false) Integer taxonomyVersion,
      @RequestParam(required = false) String nodeCode,
      @RequestParam(required = false, defaultValue = "50") @Min(1) @Max(200) Integer limit
  ) {
    return Result.success(service.evidence(taxonomyVersion, nodeCode, limit));
  }
}
