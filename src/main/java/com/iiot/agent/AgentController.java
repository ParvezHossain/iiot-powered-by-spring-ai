package com.iiot.agent;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
@ConditionalOnProperty(name = {"agent.enabled", "rag.enabled"}, havingValue = "true")
public class AgentController {
    private final AgentService agent;

    public AgentController(AgentService agent) {
        this.agent = agent;
    }

    @PostMapping("/api/agent/chat")
    public AgentService.Answer chat(@Valid @RequestBody Question request) {
        return agent.answer(request.question().strip());
    }

    public record Question(@NotBlank @Size(max = 2000) String question) {}
}
