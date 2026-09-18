package interview.guide.modules.llmprovider.dto;

public record DefaultProviderDTO(
    String defaultProvider,
    String defaultEmbeddingProvider,
    String defaultRerankProvider
) {
    public DefaultProviderDTO(String defaultProvider) {
        this(defaultProvider, null, null);
    }

    public DefaultProviderDTO(String defaultProvider, String defaultEmbeddingProvider) {
        this(defaultProvider, defaultEmbeddingProvider, null);
    }
}
