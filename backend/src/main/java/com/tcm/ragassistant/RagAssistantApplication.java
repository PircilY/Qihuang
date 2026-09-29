package com.tcm.ragassistant;

import com.tcm.ragassistant.config.RetrievalProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(RetrievalProperties.class)
public class RagAssistantApplication {

    public static void main(String[] args) {
        SpringApplication.run(RagAssistantApplication.class, args);
    }
}

