package interview.guide.modules.questiontag.service;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.modules.knowledgebase.model.KnowledgeBaseQuestionEntity;
import interview.guide.modules.questiontag.model.QuestionKnowledgeNodeEntity;
import interview.guide.modules.questiontag.model.QuestionKnowledgeNodeEntity.Status;
import interview.guide.modules.questiontag.model.QuestionKnowledgeTagEntity;
import interview.guide.modules.questiontag.model.QuestionSourceType;
import interview.guide.modules.questiontag.model.QuestionTagRelationType;
import interview.guide.modules.questiontag.model.QuestionTaggingStatus;
import interview.guide.modules.questiontag.model.QuestionTaxonomyVersionEntity;
import interview.guide.modules.questiontag.model.QuestionType;
import interview.guide.modules.questiontag.repository.QuestionKnowledgeNodeRepository;
import interview.guide.modules.questiontag.repository.QuestionTaxonomyVersionRepository;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class QuestionKnowledgeValidator {

  private static final int MAX_SECONDARY_TAGS = 4;

  private final QuestionKnowledgeNodeRepository nodeRepository;
  private final QuestionTaxonomyVersionRepository versionRepository;

  public TagSelection resolveSelection(String primaryCode, List<String> secondaryCodes) {
    List<String> secondary = normalizeCodes(secondaryCodes);
    String primary = primaryCode == null || primaryCode.isBlank() ? null : primaryCode.trim();
    if (primary == null) {
      if (!secondary.isEmpty()) {
        throw new BusinessException(ErrorCode.BAD_REQUEST, "选择次知识点前必须先选择一个主知识点");
      }
      return new TagSelection(null, null, List.of());
    }
    if (secondary.size() > MAX_SECONDARY_TAGS) {
      throw new BusinessException(ErrorCode.BAD_REQUEST, "每道题最多选择 4 个次知识点");
    }

    int version = versionRepository
        .findFirstByStatusOrderByVersionDesc(QuestionTaxonomyVersionEntity.Status.ACTIVE)
        .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "题目知识目录尚未发布"))
        .getVersion();
    Set<String> uniqueCodes = new HashSet<>();
    uniqueCodes.add(primary);
    validateAssignable(version, primary);
    for (String code : secondary) {
      if (!uniqueCodes.add(code)) {
        throw new BusinessException(ErrorCode.BAD_REQUEST, "主、次知识点不能重复: " + code);
      }
      validateAssignable(version, code);
    }
    return new TagSelection(version, primary, secondary);
  }

  /** Trusted final validation for a generated question against its captured version and Skill scope. */
  public TagSelection validateQuestionGenerationSelection(
      int taxonomyVersion,
      Set<String> allowedRoots,
      String primaryCode,
      List<String> secondaryCodes
  ) {
    if (versionRepository.findById(taxonomyVersion).isEmpty()) {
      throw new BusinessException(ErrorCode.BAD_REQUEST, "题目知识目录版本不存在: " + taxonomyVersion);
    }
    if (allowedRoots == null || allowedRoots.isEmpty()) {
      throw new BusinessException(ErrorCode.BAD_REQUEST, "本次出题的知识点范围为空");
    }
    String primary = primaryCode == null || primaryCode.isBlank() ? null : primaryCode.trim();
    if (primary == null) {
      throw new BusinessException(ErrorCode.BAD_REQUEST, "每道新题必须包含一个主知识点");
    }
    List<String> secondary = normalizeCodes(secondaryCodes);
    if (secondary.size() > MAX_SECONDARY_TAGS) {
      throw new BusinessException(ErrorCode.BAD_REQUEST, "每道题最多选择 4 个次知识点");
    }
    Set<String> unique = new HashSet<>();
    unique.add(primary);
    validateScopedAssignable(taxonomyVersion, primary, allowedRoots);
    for (String code : secondary) {
      if (!unique.add(code)) {
        throw new BusinessException(ErrorCode.BAD_REQUEST, "主、次知识点不能重复: " + code);
      }
      validateScopedAssignable(taxonomyVersion, code, allowedRoots);
    }
    return new TagSelection(taxonomyVersion, primary, secondary);
  }

  public QuestionType requireQuestionType(String value) {
    if (value == null || value.isBlank()) {
      throw new BusinessException(ErrorCode.BAD_REQUEST, "每道新题必须包含固定题型");
    }
    try {
      return QuestionType.valueOf(value.trim().toUpperCase(java.util.Locale.ROOT));
    } catch (IllegalArgumentException e) {
      throw new BusinessException(ErrorCode.BAD_REQUEST, "不支持的题目类型: " + value);
    }
  }

  public int requireDifficultyLevel(Integer level) {
    if (level == null || level < 1 || level > 5) {
      throw new BusinessException(ErrorCode.BAD_REQUEST, "每道新题的难度必须为 1～5 级");
    }
    return level;
  }

  public void validateReadyForActivation(
      KnowledgeBaseQuestionEntity question,
      List<QuestionKnowledgeTagEntity> tags
  ) {
    if (question.getDifficultyLevel() == null || question.getDifficultyLevel() < 1
        || question.getDifficultyLevel() > 5) {
      throw new BusinessException(ErrorCode.BAD_REQUEST, "启用题目必须设置 1～5 级题目难度");
    }
    if (question.getQuestionType() == null) {
      throw new BusinessException(ErrorCode.BAD_REQUEST, "启用题目必须设置固定题型");
    }
    if (question.getSourceType() == null) {
      throw new BusinessException(ErrorCode.BAD_REQUEST, "启用题目必须记录来源类型");
    }
    if (question.getSourceType() == QuestionSourceType.KNOWLEDGE_BASE
        && (question.getKbContentHash() == null || question.getKbContentHash().isBlank()
            || question.getSourceRefJson() == null || question.getSourceRefJson().isBlank())) {
      throw new BusinessException(ErrorCode.BAD_REQUEST, "知识库生成题目必须保留文件哈希和来源引用");
    }
    if (question.getTaggingStatus() != QuestionTaggingStatus.VERIFIED) {
      throw new BusinessException(ErrorCode.BAD_REQUEST, "启用题目前必须完成并确认知识点标注");
    }
    if (question.getTaxonomyVersion() == null
        || versionRepository.findById(question.getTaxonomyVersion()).isEmpty()) {
      throw new BusinessException(ErrorCode.BAD_REQUEST, "题目必须固定到一个有效的知识目录版本");
    }

    List<QuestionKnowledgeTagEntity> primary = tags.stream()
        .filter(tag -> tag.getRelationType() == QuestionTagRelationType.PRIMARY)
        .toList();
    long secondaryCount = tags.stream()
        .filter(tag -> tag.getRelationType() == QuestionTagRelationType.SECONDARY)
        .count();
    if (primary.size() != 1 || secondaryCount > MAX_SECONDARY_TAGS) {
      throw new BusinessException(ErrorCode.BAD_REQUEST, "启用题目必须有且仅有 1 个主知识点，次知识点最多 4 个");
    }
    for (QuestionKnowledgeTagEntity tag : tags) {
      if (!question.getTaxonomyVersion().equals(tag.getTaxonomyVersion())) {
        throw new BusinessException(ErrorCode.BAD_REQUEST, "同一道题的知识点必须使用相同目录版本");
      }
      validateAssignable(question.getTaxonomyVersion(), tag.getId().getNodeCode());
    }
  }

  private List<String> normalizeCodes(List<String> codes) {
    if (codes == null) {
      return List.of();
    }
    List<String> result = new ArrayList<>();
    for (String code : codes) {
      if (code == null || code.isBlank()) {
        continue;
      }
      result.add(code.trim());
    }
    return result;
  }

  private void validateAssignable(int version, String code) {
    QuestionKnowledgeNodeEntity node = nodeRepository.findByTaxonomyVersionAndCode(version, code)
        .filter(item -> item.getStatus() == Status.ACTIVE)
        .orElseThrow(() -> new BusinessException(ErrorCode.BAD_REQUEST,
            "知识点不存在或已停用: " + code));
    if (!node.isAssignable()) {
      throw new BusinessException(ErrorCode.BAD_REQUEST, "该知识节点不允许直接挂载到题目: " + code);
    }
  }

  private void validateScopedAssignable(int version, String code, Set<String> allowedRoots) {
    validateAssignable(version, code);
    boolean inScope = allowedRoots.stream().anyMatch(root -> "TECH_KNOWLEDGE".equals(root)
        || code.equals(root) || code.startsWith(root + "."));
    if (!inScope) {
      throw new BusinessException(ErrorCode.BAD_REQUEST, "知识点不在本次 Skill 出题范围内: " + code);
    }
  }

  public record TagSelection(Integer taxonomyVersion, String primaryCode, List<String> secondaryCodes) {
    public TagSelection {
      secondaryCodes = secondaryCodes == null ? List.of() : List.copyOf(secondaryCodes);
    }

    public boolean isEmpty() {
      return primaryCode == null;
    }
  }
}
