package com.iiot.rag;

import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;

/** Adds Nomic's retrieval task prefixes without putting them in stored source text. */
final class NomicEmbeddingModel implements EmbeddingModel {
    private final EmbeddingModel delegate;

    NomicEmbeddingModel(EmbeddingModel delegate) {
        this.delegate = delegate;
    }

    @Override
    public EmbeddingResponse call(EmbeddingRequest request) {
        return delegate.call(request);
    }

    @Override
    public String getEmbeddingContent(Document document) {
        return "search_document: " + document.getText();
    }

    @Override
    public float[] embed(Document document) {
        return delegate.embed(getEmbeddingContent(document));
    }

    @Override
    public float[] embed(String query) {
        return delegate.embed("search_query: " + query);
    }

    @Override
    public int dimensions() {
        return RagProperties.DIMENSIONS;
    }
}
