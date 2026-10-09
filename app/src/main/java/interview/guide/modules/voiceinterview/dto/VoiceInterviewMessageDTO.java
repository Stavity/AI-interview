package interview.guide.modules.voiceinterview.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class VoiceInterviewMessageDTO {
    private Long id;
    private Long sessionId;
    private String messageType;
    private String phase;
    private String userRecognizedText;
    private String aiGeneratedText;
    private LocalDateTime timestamp;
    private Integer sequenceNum;
    private Integer questionDifficultyLevel;
    private String questionType;
    private String primaryKnowledgeCode;
    private List<String> secondaryKnowledgeCodes;
    private Integer taxonomyVersion;
    private String taggingStatus;
}
