package com.kashi.grc.ai.provider;

import java.util.List;

/**
 * Turns text into vectors.
 *
 * Separate from LlmProvider on purpose. Embedding and chat are different
 * commercial and operational decisions: a tenant may insist chat runs on a
 * particular vendor for data-handling reasons while embeddings run somewhere
 * cheaper, and the embedding model is pinned far harder than the chat model
 * because changing it invalidates the entire index.
 */
public interface EmbeddingProvider {

    String key();

    boolean isConfigured();

    String model();

    /**
     * Vector length. Must match the Qdrant collection's configured size — a
     * mismatch is rejected by Qdrant at upsert, which is the good outcome;
     * the bad one is a silently half-migrated index.
     */
    int dimensions();

    /**
     * Batch embed. Order of the returned vectors matches the input exactly —
     * IngestionService relies on positional pairing to attach each vector to
     * its chunk, and a provider that reorders would corrupt the corpus in a way
     * no test would catch.
     */
    List<float[]> embed(List<String> texts);

    default float[] embedOne(String text) {
        List<float[]> r = embed(List.of(text));
        if (r.isEmpty()) throw new IllegalStateException("Embedding provider returned no vector");
        return r.get(0);
    }

    /** Rough token count for usage accounting. ~4 chars/token is close enough for a budget. */
    default int estimateTokens(List<String> texts) {
        int chars = 0;
        for (String t : texts) chars += t == null ? 0 : t.length();
        return Math.max(1, chars / 4);
    }
}
