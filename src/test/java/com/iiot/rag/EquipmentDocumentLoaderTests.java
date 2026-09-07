package com.iiot.rag;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EquipmentDocumentLoaderTests {
    @TempDir Path directory;

    @Test
    void bundledCorpusHasTenDocumentsWithStableChunksAndCitationMetadata() throws Exception {
        var loader = new EquipmentDocumentLoader("classpath:equipment/*.md");
        var chunks = loader.load();
        assertThat(chunks).hasSizeGreaterThan(10);
        assertThat(chunks.stream().map(d -> d.getMetadata().get("document_id")).distinct()).hasSize(10);
        assertThat(chunks.stream().map(d -> d.getId()).toList())
                .doesNotHaveDuplicates().containsExactlyElementsOf(loader.load().stream().map(d -> d.getId()).toList());
        assertThat(chunks).allSatisfy(chunk -> {
            assertThat(chunk.getMetadata()).containsKeys("source", "section", "title", "machine_ids",
                    "document_type", "document_id", "revision", "updated_at", "chunk_index");
            assertThat(chunk.getMetadata()).containsEntry("synthetic", true)
                    .containsEntry("corpus", RagProperties.CORPUS);
            assertThat(chunk.getMetadata().get("updated_at").toString()).matches("\\d{4}-\\d{2}-\\d{2}");
            assertThat(chunk.getText()).startsWith("# ").doesNotContain("document_id:", "search_document:");
        });
    }

    @Test
    void longSectionsSplitWithoutLosingTheirTailOrSourceContext() {
        String content = "The gearbox requires inspection after a sustained vibration increase. ".repeat(200)
                + "TAIL_MARKER_123";
        var loader = new EquipmentDocumentLoader("unused");
        var chunks = loader.parse("sample.md", fixture("DOC-1", content));
        assertThat(chunks).hasSizeGreaterThan(2);
        assertThat(chunks.getLast().getText()).contains("TAIL_MARKER_123");
        assertThat(chunks).allSatisfy(chunk -> {
            assertThat(chunk.getText()).startsWith("# Example machine\n\n## Inspection");
            assertThat(chunk.getText().length()).isLessThan(3500);
            assertThat(chunk.getMetadata()).containsEntry("machine_ids", List.of("SIM-001"));
        });
    }

    @Test
    void rejectsMissingEmptyAndDuplicateDocumentsBeforeIngestion() throws Exception {
        var loader = new EquipmentDocumentLoader(directory.toUri() + "*.md");
        assertThatThrownBy(loader::load).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("No equipment");
        assertThatThrownBy(() -> loader.parse("bad.md", "# No metadata"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("front matter");
        assertThatThrownBy(() -> loader.parse("empty.md", fixture("DOC-1", "")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Empty");
        Files.writeString(directory.resolve("one.md"), fixture("DOC-1", "First section."));
        Files.writeString(directory.resolve("two.md"), fixture("DOC-1", "Duplicate ID."));
        assertThatThrownBy(loader::load).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Duplicate document_id");
    }

    @Test
    void rejectsInvalidMetadataAndPreservesCrLfFiles() throws Exception {
        var loader = new EquipmentDocumentLoader(directory.toUri() + "*.md");
        assertThatThrownBy(() -> loader.parse("bad.md", fixture("DOC-1", "Text").replace("[SIM-001]", "[]")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("machine_ids");
        assertThatThrownBy(() -> loader.parse("bad.md", fixture("DOC-1", "Text").replace("document_id: DOC-1", "document_id: ")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("document_id");
        Files.writeString(directory.resolve("windows.md"), fixture("DOC-1", "Text").replace("\n", "\r\n"));
        assertThat(loader.load()).hasSize(1);
    }

    private String fixture(String id, String content) {
        return """
                ---
                document_id: %s
                document_type: equipment_manual
                machine_ids: [SIM-001]
                revision: 1
                updated_at: 2026-09-01
                synthetic: true
                ---
                # Example machine

                ## Inspection
                %s
                """.formatted(id, content);
    }
}
