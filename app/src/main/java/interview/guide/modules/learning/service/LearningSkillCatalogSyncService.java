package interview.guide.modules.learning.service;

import interview.guide.modules.interview.skill.InterviewSkillService;
import interview.guide.modules.interview.skill.InterviewSkillService.SkillCategoryDTO;
import interview.guide.modules.interview.skill.InterviewSkillService.SkillDTO;
import interview.guide.modules.learning.model.LearningSkillSourceEntity;
import interview.guide.modules.learning.repository.LearningSkillSourceRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Slf4j
@RequiredArgsConstructor
public class LearningSkillCatalogSyncService {

  private final InterviewSkillService interviewSkillService;
  private final LearningSkillSourceRepository sourceRepository;

  @EventListener(ApplicationReadyEvent.class)
  public void syncCatalogOnApplicationReady() {
    syncCatalog();
  }

  @Transactional
  public void syncCatalog() {
    Map<String, LearningSkillSourceEntity> existing = indexExistingSources();
    Set<String> discovered = new HashSet<>();

    for (SkillDTO skill : interviewSkillService.getAllSkills()) {
      for (SkillCategoryDTO category : skill.categories()) {
        String key = sourceKey(skill.id(), category.key());
        discovered.add(key);
        String description = description(skill, category);
        String contentHash = contentHash(skill, category);
        LearningSkillSourceEntity source = existing.get(key);
        if (source == null) {
          sourceRepository.save(LearningSkillSourceEntity.active(
              skill.id(), category.key(), category.label(), description, contentHash
          ));
        } else if (source.applyActiveSnapshot(category.label(), description, contentHash)) {
          sourceRepository.save(source);
        }
      }
    }

    existing.entrySet().stream()
        .filter(entry -> !discovered.contains(entry.getKey()))
        .map(Map.Entry::getValue)
        .filter(LearningSkillSourceEntity::markUnavailable)
        .forEach(sourceRepository::save);

    log.info("学习 Skill 目录同步完成: discovered={}, existing={}", discovered.size(), existing.size());
  }

  public static String contentHash(SkillDTO skill, SkillCategoryDTO category) {
    String source = String.join("\n",
        nullToEmpty(skill.id()),
        nullToEmpty(skill.name()),
        nullToEmpty(skill.description()),
        nullToEmpty(skill.persona()),
        nullToEmpty(category.key()),
        nullToEmpty(category.label()),
        nullToEmpty(category.priority()),
        nullToEmpty(category.ref()),
        Boolean.toString(category.shared())
    );
    try {
      byte[] digest = MessageDigest.getInstance("SHA-256").digest(source.getBytes(StandardCharsets.UTF_8));
      StringBuilder hex = new StringBuilder(digest.length * 2);
      for (byte value : digest) {
        hex.append(String.format("%02x", value));
      }
      return hex.toString();
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("JVM 不支持 SHA-256", e);
    }
  }

  private Map<String, LearningSkillSourceEntity> indexExistingSources() {
    Map<String, LearningSkillSourceEntity> result = new HashMap<>();
    for (LearningSkillSourceEntity source : sourceRepository.findAll()) {
      result.put(sourceKey(source.getSkillId(), source.getCategoryKey()), source);
    }
    return result;
  }

  private static String description(SkillDTO skill, SkillCategoryDTO category) {
    return skill.name() + " / " + category.label() + "\n" + nullToEmpty(skill.description());
  }

  private static String sourceKey(String skillId, String categoryKey) {
    return skillId + "\u0000" + categoryKey;
  }

  private static String nullToEmpty(String value) {
    return value == null ? "" : value;
  }
}
