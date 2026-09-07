package com.iiot.rag;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
@ConditionalOnProperty(name = "rag.enabled", havingValue = "true")
public class RagQueryController {
    private final RagAnswerService answers;

    public RagQueryController(RagAnswerService answers) {
        this.answers = answers;
    }

    @PostMapping("/api/rag/query")
    public RagAnswerService.Answer query(@Valid @RequestBody Question request) {
        return answers.answer(request.question().strip());
    }

    public record Question(@NotBlank @Size(max = 2000) String question) {}
}
