package interview.guide.modules.learning;

import interview.guide.common.result.Result;
import interview.guide.modules.learning.service.PersonalizedLearningPathService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/learning/personalized-path")
@RequiredArgsConstructor
public class PersonalizedLearningPathController {

  private final PersonalizedLearningPathService service;

  @GetMapping
  public Result<PersonalizedLearningPathService.PathSnapshot> current() {
    return Result.success(service.current());
  }

  @PostMapping("/regenerate")
  public Result<PersonalizedLearningPathService.PathSnapshot> regenerate(
      @Valid @RequestBody(required = false) RegenerateRequest request
  ) {
    return Result.success(service.regenerate(request == null ? null : request.goal()));
  }

  @PostMapping("/items/{itemId}/complete")
  public Result<PersonalizedLearningPathService.PathSnapshot> complete(@PathVariable long itemId) {
    return Result.success(service.completeItem(itemId));
  }

  public record RegenerateRequest(@Size(max = 1000) String goal) {}
}
