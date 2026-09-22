package com.iiot.agent;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Agent chat", description = "Conversational telemetry and equipment assistance. Requires agent.enabled=true and rag.enabled=true.")
@RestController
@ConditionalOnProperty(name = {"agent.enabled", "rag.enabled"}, havingValue = "true")
public class AgentController {
    private final AgentService agent;

    public AgentController(AgentService agent) {
        this.agent = agent;
    }

    @Operation(operationId = "chatWithAgent", summary = "Ask the conversational agent",
            description = "Uses telemetry and equipment tools as needed. Omit conversationId to start a conversation; reuse the returned UUID for follow-ups. Memory is in-process and expires on restart or after the configured idle timeout. Insufficient evidence is reported in a successful response.")
    @ApiResponse(responseCode = "200", description = "Successful response")
    @ApiResponse(responseCode = "400", description = "Malformed request, blank question, question longer than 2000 characters, or invalid conversation UUID", content = @Content)
    @ApiResponse(responseCode = "503", description = "Required AI service is unavailable", content = @Content)
    @PostMapping("/api/agent/chat")
    public AgentService.ConversationAnswer chat(@Valid @RequestBody Question request) {
        return agent.chat(request.question().strip(), request.conversationId());
    }

    @Schema(name = "AgentQuestion", description = "Question submitted to the API")
    public record Question(@Schema(description = "Nonblank question", example = "What does E204 mean?", minLength = 1, maxLength = 2000)
                           @NotBlank @Size(max = 2000) String question,
            @Schema(description = "Existing conversation UUID; omit to create a conversation") java.util.UUID conversationId) {}
}
