package com.ai.reviewer.backend.llm;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;

public class ClaudeClient {

  private static final String API_URL = "https://api.anthropic.com/v1/messages";
  private static final String ANTHROPIC_VERSION = "2023-06-01"; // per docs :contentReference[oaicite:1]{index=1}

  private final HttpClient http = HttpClient.newBuilder()
      .connectTimeout(Duration.ofSeconds(20))
      .build();

  private final ObjectMapper mapper = new ObjectMapper();

  private final String apiKey;
  private final String model;

  public ClaudeClient() {
    this.apiKey = System.getenv("ANTHROPIC_API_KEY");
    if (apiKey == null || apiKey.isBlank()) {
      throw new IllegalStateException("ANTHROPIC_API_KEY env var is not set");
    }
    // Safe default model from Anthropic examples (you can change later)
    this.model = Optional.ofNullable(System.getenv("ANTHROPIC_MODEL"))
        .filter(s -> !s.isBlank())
        .orElse("claude-3-5-sonnet-latest");
  }

  /**
   * Returns Claude's text output (we will ask it to output JSON only).
   */
  public String completeJsonOnly(String systemPrompt, String userPrompt, int maxTokens) throws Exception {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("model", model);
    payload.put("max_tokens", maxTokens);
    payload.put("system", systemPrompt);

    List<Map<String, Object>> messages = new ArrayList<>();
    messages.add(Map.of("role", "user", "content", userPrompt));
    payload.put("messages", messages);

    String body = mapper.writeValueAsString(payload);

    HttpRequest req = HttpRequest.newBuilder()
        .uri(URI.create(API_URL))
        .timeout(Duration.ofSeconds(60))
        .header("x-api-key", apiKey)
        .header("anthropic-version", ANTHROPIC_VERSION)
        .header("content-type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(body))
        .build();

    HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());

    if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
      throw new RuntimeException("Anthropic API error " + resp.statusCode() + ": " + resp.body());
    }

    // Response JSON format includes: content: [{type:"text", text:"..."}]
    Map<?, ?> json = mapper.readValue(resp.body(), Map.class);
    Object contentObj = json.get("content");
    if (!(contentObj instanceof List<?> contentList) || contentList.isEmpty()) {
      throw new RuntimeException("Unexpected Anthropic response (missing content): " + resp.body());
    }

    Object first = contentList.get(0);
    if (!(first instanceof Map<?, ?> firstMap) || firstMap.get("text") == null) {
      throw new RuntimeException("Unexpected Anthropic response (missing content[0].text): " + resp.body());
    }

    return String.valueOf(firstMap.get("text"));
  }
}
