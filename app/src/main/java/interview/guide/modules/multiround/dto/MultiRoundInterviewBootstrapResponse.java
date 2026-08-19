package interview.guide.modules.multiround.dto;

public record MultiRoundInterviewBootstrapResponse(
    String sessionId,
    Long interviewId,
    String status,
    int currentRoundNo,
    boolean resumed
) {}
