package com.coldchainos.ai.agent;

import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.service.AiServices;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Spring Factory wiring the Gemini ChatLanguageModel and domain tools into the AiIncidentCommanderAgent.
 */
@Configuration
public class AiAgentFactory {

    @Bean
    public AiIncidentCommanderAgent aiIncidentCommanderAgent(
        ChatLanguageModel chatLanguageModel,
        IncidentInvestigationTools tools
    ) {
        return AiServices.builder(AiIncidentCommanderAgent.class)
            .chatLanguageModel(chatLanguageModel)
            .tools(tools)
            .build();
    }
}
