package com.iiot.rag;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingOptions;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.TokenCountBatchingStrategy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class NomicEmbeddingModelTests {
    @Test
    void vectorStoreBatchAndQueryPathsUseDifferentTaskPrefixes() {
        var delegate = mock(EmbeddingModel.class);
        when(delegate.call(any())).thenAnswer(invocation -> {
            EmbeddingRequest request = invocation.getArgument(0);
            assertThat(request.getInstructions()).containsExactly("search_document: Cooling screen blocked");
            return new EmbeddingResponse(List.of(new Embedding(new float[]{1, 0}, 0)));
        });
        var model = new NomicEmbeddingModel(delegate);
        var document = new Document("Cooling screen blocked");
        assertThat(model.embed(List.of(document), EmbeddingOptions.builder().build(), new TokenCountBatchingStrategy()))
                .hasSize(1);
        model.embed("Why is the compressor hot?");
        verify(delegate).embed("search_query: Why is the compressor hot?");
        assertThat(document.getText()).isEqualTo("Cooling screen blocked");
    }
}
