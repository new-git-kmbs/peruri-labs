package com.ai.platform.api.finance;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.*;

@Service
public class AiCategorizationService {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final RestClient restClient = RestClient.create();

    private static final int MAX_ITEMS_PER_LLM_CALL = 25;

    public Map<Integer, String> categorize(List<Map<String, Object>> items) {

        Map<Integer, String> txnIdToCategory = new HashMap<>();

        List<List<Map<String, Object>>> chunks = chunk(items, MAX_ITEMS_PER_LLM_CALL);

        for (List<Map<String, Object>> chunk : chunks) {

            String prompt = buildPrompt(chunk);
            String response = callAnthropic(prompt);
            Map<String, Object> aiJson = parseJson(response);

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> categories =
                    (List<Map<String, Object>>) aiJson.get("categories");

            if (categories == null) continue;

            for (Map<String, Object> c : categories) {
                String cat = String.valueOf(c.get("category"));
                List<Integer> txnIds = toIntList(c.get("txnIds"));

                for (Integer id : txnIds) {
                    txnIdToCategory.put(id, cat);
                }
            }
        }

        return txnIdToCategory;
    }

    private String buildPrompt(List<Map<String, Object>> items) {
        try {
            String json = objectMapper.writeValueAsString(items);

            return """
You are a financial categorization engine.

Assign each transaction id to exactly one category.

Allowed categories:
["Subscriptions","Bills","Dining","Groceries","Transport","Shopping","Health","Travel","Entertainment","Fees","Refunds","Other"]

Return STRICT JSON ONLY:

{
  "categories": [
    {
      "category": string,
      "txnIds": [number]
    }
  ]
}

Transactions:
""" + json;

        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private String callAnthropic(String prompt) {

        String apiKey = System.getenv("ANTHROPIC_API_KEY");
        if (apiKey == null || apiKey.isBlank())
            throw new IllegalArgumentException("Missing ANTHROPIC_API_KEY");

        String model = System.getenv("ANTHROPIC_MODEL");
        if (model == null || model.isBlank())
            throw new IllegalArgumentException("Missing ANTHROPIC_MODEL");

        System.out.println("Using Anthropic model: " + model);

        Map<String, Object> body = Map.of(
                "model", model,
                "max_tokens", 700,
                "temperature", 0.2,
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
        if (content.isEmpty())
            throw new IllegalStateException("Empty Anthropic content");

        Map<?, ?> first = (Map<?, ?>) content.get(0);

        return String.valueOf(first.get("text"));
    }

    private Map<String, Object> parseJson(String raw) {
        try {
            String s = raw.trim();

            // Strip markdown fences if Claude adds them
            if (s.startsWith("```")) {
                s = s.replaceAll("^```[a-zA-Z]*\\s*", "");
                s = s.replaceAll("\\s*```$", "");
                s = s.trim();
            }

            return objectMapper.readValue(
                    s,
                    new TypeReference<Map<String, Object>>() {}
            );

        } catch (Exception e) {
            throw new RuntimeException("Invalid AI JSON: " + raw, e);
        }
    }

    private List<List<Map<String, Object>>> chunk(List<Map<String, Object>> items, int size) {
        List<List<Map<String, Object>>> out = new ArrayList<>();
        for (int i = 0; i < items.size(); i += size) {
            out.add(items.subList(i, Math.min(i + size, items.size())));
        }
        return out;
    }

    private List<Integer> toIntList(Object o) {
        if (!(o instanceof List<?> list)) return List.of();
        List<Integer> out = new ArrayList<>();
        for (Object x : list) {
            out.add(Integer.parseInt(String.valueOf(x)));
        }
        return out;
    }
}