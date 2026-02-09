package com.ai.reviewer.backend.api;

import com.ai.reviewer.backend.llm.ClaudeClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequestMapping("/api")
public class ReviewController {

  private final ClaudeClient claude = new ClaudeClient();
  private final ObjectMapper mapper = new ObjectMapper();

  private static final String SYSTEM_PROMPT = """
You are a senior Product Owner and QA reviewer.

Review the provided user story/requirement for clarity, completeness, and testability.
Base your analysis ONLY on the text provided. Do not invent domain rules.
If context is missing, ask clarifying questions rather than guessing.

Return ONLY valid JSON. No markdown. No extra text.
""";

  @PostMapping("/review")
  public Map<String, Object> review(@RequestBody Map<String, Object> req) throws Exception {

    String context = String.valueOf(req.getOrDefault("context", "")).trim();
    String story = String.valueOf(req.getOrDefault("story", "")).trim();
    String ac = String.valueOf(req.getOrDefault("acceptanceCriteria", "")).trim();

    if (story.isBlank()) {
      return Map.of("rating", Map.of(
          "score_1_to_5", 1,
          "label", "Not Passed (Unusable)",
          "one_line_summary", "Story is required.",
          "critical", 1, "major", 0, "minor", 0
      ), "data", Map.of());
    }

String userPrompt = """
Review the following product requirement and return JSON in exactly this shape:

{
  "rating": {
    "score_1_to_5": 1-5,
    "label": "Pass (Excellent) | Pass (Minor fixes) | Acceptable (Needs improvements) | Not Passed (High risk) | Not Passed (Unusable)",
    "one_line_summary": "string",
    "critical": number,
    "major": number,
    "minor": number
  },
  "data": {
    "missing_acceptance_criteria": [{"title":"", "details":"", "severity":"critical|major|minor"}],
    "ambiguous_language": [{"quote":"", "why_ambiguous":"", "suggested_rewrite":"", "severity":"critical|major|minor"}],
    "edge_cases": [{"title":"", "scenario":"", "expected_behavior_question":"", "severity":"critical|major|minor"}],
    "non_testable_or_weak_criteria": [{"quote":"", "issue":"", "testable_rewrite":"", "severity":"critical|major|minor"}],
    "missing_context_questions": [{"question":"", "why_needed":""}]
  },
  "rewrite": {
    "user_story": "string",
    "acceptance_criteria": ["string"]
  },
  "jira_comment_md": "string"
}

Rules:
- Use empty arrays [] when none.
- Do NOT add extra keys beyond rating, data, rewrite, jira_comment_md.
- Rewrite must be realistic and directly usable (not generic).
- acceptance_criteria must be testable; use Given/When/Then where possible.
- jira_comment_md must be Jira Markdown and include:
  * Score + label
  * One-line summary
  * Top issues (critical/major)
  * Missing AC (if any)
  * Edge cases (if any)
  * Clarifying questions (if any)
- Do NOT output anything except valid JSON.
- Keep jira_comment_md under 1200 characters.


Context:
%s

User Story / Requirement:
%s

Acceptance Criteria:
%s
""".formatted(
    context.isBlank() ? "(none provided)" : context,
    story,
    ac.isBlank() ? "(not provided)" : ac
);


try {
  String text = claude.completeJsonOnly(SYSTEM_PROMPT, userPrompt, 1400);

  // Attempt 1: parse as-is
  try {
    return mapper.readValue(text, Map.class);
  } catch (Exception first) {

    // Attempt 2: ask Claude to re-output VALID JSON ONLY (same as before)
    String repair1 = userPrompt
        + "\n\nYour last response was invalid JSON. Return ONLY valid JSON for the exact shape specified. No extra text.";
    String text2 = claude.completeJsonOnly(SYSTEM_PROMPT, repair1, 1400);

    try {
      return mapper.readValue(text2, Map.class);
    } catch (Exception second) {

      // Attempt 3 (strong): ask Claude to FIX the broken JSON it produced
      String repair2 = """
You returned invalid JSON. Fix it.

Rules:
- Return ONLY valid JSON (no markdown, no commentary).
- Keep the exact schema requested earlier.
- Preserve as much content as possible.
- Ensure all strings are properly quoted and escaped.
- Ensure the JSON is complete and closes all braces/brackets.

Here is the broken JSON to fix:
%s
""".formatted(text2);

      String text3 = claude.completeJsonOnly(SYSTEM_PROMPT, repair2, 1400);
      return mapper.readValue(text3, Map.class);
    }
  }

} catch (Exception e) {
  return Map.of(
      "rating", Map.of(
          "score_1_to_5", 1,
          "label", "Not Passed (Unusable)",
          "one_line_summary", "AI call failed or returned invalid JSON. See error.message.",
          "critical", 1, "major", 0, "minor", 0
      ),
      "data", Map.of(
          "missing_acceptance_criteria", List.of(),
          "ambiguous_language", List.of(),
          "edge_cases", List.of(),
          "non_testable_or_weak_criteria", List.of(),
          "missing_context_questions", List.of()
      ),
      "rewrite", Map.of(
          "user_story", "",
          "acceptance_criteria", List.of()
      ),
      "jira_comment_md", "",
      "error", Map.of(
          "message", e.getMessage(),
          "hint", "This usually means the model returned malformed/truncated JSON. Try again; if it repeats, we will shorten jira_comment_md or reduce output size."
      )
  );
}



  }
}
