package interview.guide.modules.interview.task;
import interview.guide.common.async.*;
import interview.guide.common.ai.LlmProviderRegistry;
import interview.guide.modules.interview.model.InterviewQuestionDTO;
import interview.guide.modules.interview.repository.InterviewSessionRepository;
import interview.guide.modules.interview.service.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import java.util.*;
@Component @RequiredArgsConstructor
public class InterviewEvaluateTaskHandler implements AsyncTaskHandler {
    private final InterviewSessionRepository repository;
    private final AnswerEvaluationService evaluation;
    private final InterviewPersistenceService persistence;
    private final LlmProviderRegistry providers;
    private final ObjectMapper mapper;
    public AsyncTaskType type() { return AsyncTaskType.INTERVIEW_EVALUATE; }
    public boolean exists(String id) { return repository.findBySessionId(id).isPresent(); }
    public Map<String, String> snapshot(String id) {
        var session = repository.findBySessionIdWithResume(id).orElseThrow();
        List<InterviewQuestionDTO> questions = new ArrayList<>(mapper.readValue(session.getQuestionsJson(), new TypeReference<List<InterviewQuestionDTO>>() {}));
        for (var answer : persistence.findAnswersBySessionId(id)) {
            int index = answer.getQuestionIndex();
            if (index >= 0 && index < questions.size()) questions.set(index, questions.get(index).withAnswer(answer.getUserAnswer()));
        }
        return Map.of("questions", mapper.writeValueAsString(questions),
            "provider", Objects.toString(session.getLlmProvider(), ""),
            "resume", session.getResume() == null ? "" : Objects.toString(session.getResume().getResumeText(), ""));
    }
    public void execute(TaskExecutionContext context) {
        List<InterviewQuestionDTO> questions = mapper.readValue(context.required("questions"), new TypeReference<List<InterviewQuestionDTO>>() {});
        var report = evaluation.evaluateInterview(providers.getChatClientOrDefault(context.required("provider")),
            context.businessId(), context.required("resume"), questions);
        context.complete(() -> persistence.saveReport(context.businessId(), report));
    }
    public void updateStatus(String id, AsyncTaskState state, String error) {
        persistence.updateEvaluateStatus(id, TaskStatusMapping.legacy(state), error);
    }
}
