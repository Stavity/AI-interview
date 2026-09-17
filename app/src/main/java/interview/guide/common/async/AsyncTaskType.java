package interview.guide.common.async;

public enum AsyncTaskType {
    RESUME_ANALYZE("resume-analyze"), KB_VECTORIZE("kb-vectorize"),
    INTERVIEW_EVALUATE("interview-evaluate"), VOICE_EVALUATE("voice-evaluate"),
    QUESTION_GENERATE("question-generate");
    private final String route;
    AsyncTaskType(String route) { this.route = route; }
    public String topic() { return "ai-interview." + route + ".v1"; }
}
