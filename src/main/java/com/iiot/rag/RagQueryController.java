package com.iiot.rag;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.jspecify.annotations.Nullable;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Grounded answers", description = "Answers with validated source quotes. ADMIN only. Requires rag.enabled=true for execution.")
@RestController
@ApiResponse(responseCode = "401", description = "Missing or invalid JWT")
@ApiResponse(responseCode = "403", description = "ADMIN role required")
public class RagQueryController {
    private final @Nullable RagAnswerService answers;

    public RagQueryController(@Nullable RagAnswerService answers) {
        this.answers = answers;
    }

    @Operation(operationId = "queryEquipmentKnowledge", summary = "Ask an equipment knowledge question",
            description = "Returns an answer with server-validated quotes. Missing or invalid evidence returns HTTP 200 with insufficientEvidence=true.")
    @ApiResponse(responseCode = "200", description = "Successful response")
    @ApiResponse(responseCode = "400", description = "Malformed request, blank question, question longer than 2000 characters", content = @Content)
    @ApiResponse(responseCode = "503", description = "Required AI service is unavailable", content = @Content)
    @PostMapping("/api/rag/query")
    public RagAnswerService.Answer query(@Valid @RequestBody Question request) {
        if (answers == null) throw new ResponseStatusException(
                HttpStatus.SERVICE_UNAVAILABLE, "RAG is disabled; enable RAG_ENABLED");
        return answers.answer(request.question().strip());
    }

    @Schema(name = "RagQuestion", description = "Question submitted to the API")
    public record Question(@Schema(description = "Nonblank question", example = "What does E204 mean?", minLength = 1, maxLength = 2000)
                           @NotBlank @Size(max = 2000) String question) {}
}
