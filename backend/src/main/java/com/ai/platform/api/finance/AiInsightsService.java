package com.ai.platform.api.finance;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

@Service
public class AiInsightsService {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final RestClient restClient = RestClient.create();

    public Map<String, Object> generateInsights(Map<String, Object> summary) {

        try {
            String apiKey = System.getenv("ANTHROPIC_API_KEY");
            if (apiKey == null || apiKey.isBlank())
                throw new IllegalStateException("Missing ANTHROPIC_API_KEY");

            String model = System.getenv("ANTHROPIC_MODEL");
            if (model == null || model.isBlank())
                throw new IllegalStateException("Missing ANTHROPIC_MODEL");

            System.out.println("Using Anthropic model (insights): " + model);

            String json = objectMapper.writeValueAsString(summary);

            String prompt = """
You are a financial behavior analyst.

Return STRICT JSON only (no markdown):

{
  "highlights": [string],
  "topSpendingCategory": string,
  "topMerchant": string,
  "concentrationNotes": [string],
  "optimizationIdeas": [string],
  "anomalies": [string]
}

Summary:
""" + json;

            Map<String, Object> body = Map.of(
                    "model", model,
                    "max_tokens", 600,
                    "temperature", 0.3,
                    "messages", List.of(
                            Map.of("role", "user", "content", prompt)
                    )
            );

            Map<String, Object> resp = restClient.post()
                    .uri("https://api.anthropic.com/v1/messages")
                    .header("x-api-key", apiKey)
                    .header("anthropic-version", "2023-06-01")
                    .header("content-type", "application/json")
                    .body(body)
                    .retrieve()
                    .body(new ParameterizedTypeReference<Map<String, Object>>() {});

            if (resp == null || !resp.containsKey("content"))
                throw new IllegalStateException("Invalid Anthropic response");

            List<?> content = (List<?>) resp.get("content");
            Map<?, ?> first = (Map<?, ?>) content.get(0);

            String raw = String.valueOf(first.get("text")).trim();

            // Remove markdown fences if Claude adds them
            if (raw.startsWith("```")) {
                raw = raw.replaceAll("^```[a-zA-Z]*\\s*", "");
                raw = raw.replaceAll("\\s*```$", "");
                raw = raw.trim();
            }

            return objectMapper.readValue(
                    raw,
                    new TypeReference<>() {}
            );

        } catch (Exception e) {
            throw new RuntimeException("AI insights failed", e);
        }
    }
}