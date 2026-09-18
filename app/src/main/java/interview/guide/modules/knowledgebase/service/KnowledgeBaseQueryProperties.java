package interview.guide.modules.knowledgebase.service;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "app.ai.rag")
public class KnowledgeBaseQueryProperties {

    private Rewrite rewrite = new Rewrite();
    private Search search = new Search();
    private History history = new History();
    private Rerank rerank = new Rerank();
    private Hybrid hybrid = new Hybrid();
    private Context context = new Context();
    private MultiQuery multiQuery = new MultiQuery();
    private String systemPromptPath = "classpath:prompts/knowledgebase-query-system.st";
    private String userPromptPath = "classpath:prompts/knowledgebase-query-user.st";
    private String rewritePromptPath = "classpath:prompts/knowledgebase-query-rewrite.st";

    @Data
    public static class Rewrite {
        private boolean enabled = true;
    }

    @Data
    public static class Search {
        private int shortQueryLength = 4;
        private int mediumQueryLength = 12;
        private int topkShort = 20;
        private int topkMedium = 12;
        private int topkLong = 8;
        private double minScoreShort = 0.25;
        private double minScoreDefault = 0.28;
    }

    @Data
    public static class History {
        private boolean enabled = true;
        private int maxMessages = 10;
    }

    @Data
    public static class Rerank {
        private boolean enabled = false;
        private int candidateTopK = 30;
        private int topN = 6;
        private double minScore = 0.1;
        private String model = "gte-rerank-v2";
    }

    @Data
    public static class Hybrid {
        private boolean enabled = false;
        private int keywordTopK = 20;
        private int rrfK = 60;
    }

    @Data
    public static class Context {
        private int expandWindow = 1;
        private int maxContextChars = 8000;
        private boolean includeSourceHeading = true;
    }

    @Data
    public static class MultiQuery {
        private boolean enabled = false;
        private int variantCount = 3;
    }
}
