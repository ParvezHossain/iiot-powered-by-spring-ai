package com.iiot.rag;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

import org.springframework.ai.document.Document;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/** Reads the corpus front matter, then splits within Markdown sections. */
public class EquipmentDocumentLoader {
    private static final Pattern TITLE = Pattern.compile("(?m)^# (.+)$");
    private static final Pattern SECTION = Pattern.compile("(?m)^## (.+)$");
    private final String pattern;
    private final TokenTextSplitter splitter = TokenTextSplitter.builder()
            .withChunkSize(300).withMinChunkSizeChars(100).withMinChunkLengthToEmbed(0)
            .withKeepSeparator(true).build();

    public EquipmentDocumentLoader(String pattern) {
        this.pattern = pattern;
    }

    public List<Document> load() throws IOException {
        Resource[] resources = new PathMatchingResourcePatternResolver().getResources(pattern);
        if (resources.length == 0) {
            throw new IllegalArgumentException("No equipment documents matched " + pattern);
        }
        Arrays.sort(resources, Comparator.comparing(Resource::getFilename));
        var ids = new HashSet<String>();
        var chunks = new ArrayList<Document>();
        for (Resource resource : resources) {
            String source = resource.getFilename();
            String text = resource.getContentAsString(StandardCharsets.UTF_8).replace("\r\n", "\n");
            List<Document> documentChunks = parse(source, text);
            String id = (String) documentChunks.getFirst().getMetadata().get("document_id");
            if (!ids.add(id)) {
                throw new IllegalArgumentException("Duplicate document_id: " + id);
            }
            chunks.addAll(documentChunks);
        }
        return List.copyOf(chunks);
    }

    List<Document> parse(String source, String text) {
        if (!text.startsWith("---\n") || text.indexOf("\n---\n", 4) < 0) {
            throw new IllegalArgumentException("Missing YAML front matter: " + source);
        }
        int end = text.indexOf("\n---\n", 4);
        var options = new LoaderOptions();
        options.setAllowDuplicateKeys(false);
        Object parsed = new Yaml(new SafeConstructor(options)).load(text.substring(4, end));
        if (!(parsed instanceof Map<?, ?> front)) {
            throw new IllegalArgumentException("Invalid metadata: " + source);
        }
        var metadata = new LinkedHashMap<String, Object>();
        for (String key : List.of("document_id", "document_type", "revision", "updated_at")) {
            Object value = front.get(key);
            if (value == null || value.toString().isBlank()) {
                throw new IllegalArgumentException("Missing " + key + ": " + source);
            }
            metadata.put(key, value instanceof java.util.Date date
                    ? date.toInstant().toString().substring(0, 10) : value.toString());
        }
        if (!(front.get("machine_ids") instanceof List<?> machines) || machines.isEmpty()
                || machines.stream().anyMatch(id -> !(id instanceof String s) || s.isBlank())
                || !(front.get("synthetic") instanceof Boolean)) {
            throw new IllegalArgumentException("Invalid machine_ids or synthetic metadata: " + source);
        }
        metadata.put("machine_ids", front.get("machine_ids"));
        metadata.put("synthetic", front.get("synthetic"));
        metadata.put("source", source);
        metadata.put("corpus", RagProperties.CORPUS);
        metadata.put("embedding_model", RagProperties.MODEL);
        metadata.put("chunker_version", "sections-300-v1");
        String body = text.substring(end + 5).strip();
        var titleMatch = TITLE.matcher(body);
        if (!titleMatch.find()) {
            throw new IllegalArgumentException("Missing document title: " + source);
        }
        String title = titleMatch.group(1);
        metadata.put("title", title);
        String section = "Overview";
        int start = titleMatch.end();
        var headings = SECTION.matcher(body);
        var chunks = new ArrayList<Document>();
        while (headings.find()) {
            append(chunks, metadata, section, body.substring(start, headings.start()));
            section = headings.group(1);
            start = headings.end();
        }
        append(chunks, metadata, section, body.substring(start));
        if (chunks.isEmpty()) {
            throw new IllegalArgumentException("Empty equipment document: " + source);
        }
        return chunks;
    }

    private void append(List<Document> chunks, Map<String, Object> metadata, String section, String content) {
        if (content.isBlank()) {
            return;
        }
        for (Document part : splitter.apply(List.of(new Document(content.strip())))) {
            var chunkMetadata = new LinkedHashMap<>(metadata);
            chunkMetadata.put("section", section);
            chunkMetadata.put("chunk_index", chunks.size());
            String text = "# " + metadata.get("title") + "\n\n## " + section + "\n\n" + part.getText();
            String identity = RagProperties.CORPUS + ":" + metadata.get("document_id") + ":" + chunks.size() + ":" + text;
            String id = UUID.nameUUIDFromBytes(identity.getBytes(StandardCharsets.UTF_8)).toString();
            chunks.add(new Document(id, text, chunkMetadata));
        }
    }
}
