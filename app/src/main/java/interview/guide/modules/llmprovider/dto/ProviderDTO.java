package interview.guide.modules.llmprovider.dto;

import lombok.Builder;

@Builder
public record ProviderDTO(
    String id,
    String baseUrl,
    String maskedApiKey,
    String model,
    String embeddingModel,
    Integer embeddingDimensions,
    boolean supportsEmbedding,
    boolean supportsRerank,
    String rerankModel,
    Double temperature,
    boolean defaultChatProvider,
    boolean defaultEmbeddingProvider,
    boolean defaultRerankProvider
) {}
