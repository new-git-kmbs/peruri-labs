package com.ai.platform.api.finance;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.multipart.MultipartFile;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.Pattern;

@Service
public class TransactionsService {

    private static final List<DateTimeFormatter> DATE_FORMATS = List.of(
            DateTimeFormatter.ISO_LOCAL_DATE,
            DateTimeFormatter.ofPattern("M/d/uuuu"),
            DateTimeFormatter.ofPattern("MM/dd/uuuu"),
            DateTimeFormatter.ofPattern("uuuu-M-d"),
            DateTimeFormatter.ofPattern("uuuu/MM/dd")
    );

    private static final Pattern NON_NUMERIC = Pattern.compile("[^0-9.\\-]");

    private final ObjectMapper objectMapper = new ObjectMapper();

    // Spring's RestClient (Spring 6 / Boot 3)
    private final RestClient restClient = RestClient.create();

    // Tune these if needed (keeps each LLM call safely small)
    private static final int MAX_ITEMS_PER_LLM_CALL = 25;
    private static final int MAX_TOTAL_ITEMS = 2000;

    // ---------------- Month filtering ----------------

    private static YearMonth parseMonthKey(String monthKey) {
        return YearMonth.parse(monthKey); // "YYYY-MM"
    }

    private static LocalDate monthStartInclusive(String monthKey) {
        return parseMonthKey(monthKey).atDay(1);
    }

    private static LocalDate nextMonthStartExclusive(String monthKey) {
        return parseMonthKey(monthKey).plusMonths(1).atDay(1);
    }

    private static List<Txn> filterTxnsToMonth(List<Txn> txns, String monthKey) {
        if (monthKey == null || monthKey.isBlank()) return txns;

        LocalDate start = monthStartInclusive(monthKey);
        LocalDate endExcl = nextMonthStartExclusive(monthKey);

        List<Txn> out = new ArrayList<>();
        for (Txn t : txns) {
            LocalDate d = t.date();
            if (d != null && !d.isBefore(start) && d.isBefore(endExcl)) {
                out.add(t);
            }
        }
        return out;
    }

    // CHANGED: multi-file entry point + selected month
    // monthKey format: "YYYY-MM" (example: "2026-02")
    public Map<String, Object> aiAnalyze(List<MultipartFile> files, String monthKey) {
        BigDecimal billPaymentsTotal = BigDecimal.ZERO;
        BigDecimal payrollTotal = BigDecimal.ZERO; // NEW

        // Parse + merge transactions from all files
        List<Txn> txnsAll = parseCsv(files);

        // Filter ONLY selected month transactions
        List<Txn> txns = filterTxnsToMonth(txnsAll, monthKey);

        // Track date range for "usable" items (non-payments, non-zero)
        LocalDate minDate = null;
        LocalDate maxDate = null;

        // Build LLM items (exclude payments but track them)
        List<Map<String, Object>> items = new ArrayList<>();
        int id = 0;

        for (Txn t : txns) {
            String desc = t.description();
            BigDecimal amt = t.amount();

            if (amt == null || amt.compareTo(BigDecimal.ZERO) == 0) continue;

            // NEW: Track payroll (positive inflows) separately, but do NOT send to AI
            if (amt.compareTo(BigDecimal.ZERO) > 0 && isPayroll(desc)) {
                payrollTotal = payrollTotal.add(amt);
                continue;
            }

            // Exclude payments (NOT spend, NOT refund), but track total
            if (isPayment(desc)) {
                if (amt.compareTo(BigDecimal.ZERO) < 0) {
                    billPaymentsTotal = billPaymentsTotal.add(amt.abs());
                } else {
                    billPaymentsTotal = billPaymentsTotal.add(amt);
                }
                continue;
            }

            // Date range (only for usable items)
            if (minDate == null || t.date().isBefore(minDate)) minDate = t.date();
            if (maxDate == null || t.date().isAfter(maxDate)) maxDate = t.date();

            String kind;
            BigDecimal value;

            if (amt.compareTo(BigDecimal.ZERO) < 0) {
                kind = "expense";
                value = amt.abs();
            } else {
                kind = "refund";
                value = amt; // already positive
            }

            Map<String, Object> m = new HashMap<>();
            m.put("id", ++id);
            m.put("date", t.date().toString());
            m.put("merchant", normalizeMerchant(desc));
            m.put("amount", value);     // always positive value
            m.put("kind", kind);        // "expense" or "refund"
            items.add(m);

            // hard guardrail (avoid extreme uploads / too many calls)
            if (items.size() >= MAX_TOTAL_ITEMS) break;
        }

        if (items.isEmpty()) {
            throw new IllegalArgumentException("No usable transactions found (after excluding payments).");
        }

        // Chunk the LLM calls so we never exceed small token budgets
        Map<Integer, String> txnIdToCategory = new HashMap<>();
        List<List<Map<String, Object>>> chunks = chunkItems(items, MAX_ITEMS_PER_LLM_CALL);

        int expectedTxnCount = items.size();

        for (int i = 0; i < chunks.size(); i++) {
            List<Map<String, Object>> chunk = chunks.get(i);

            String prompt = buildPrompt(chunk);

            String llmText = callAnthropic(prompt);

            Map<String, Object> aiJson = parseJsonObject(llmText);

            // Validate ids for THIS chunk (not 1..N global)
            Set<Integer> expectedIds = chunk.stream()
                    .map(m -> toInt(m.get("id")))
                    .collect(java.util.stream.Collectors.toSet());

            List<Integer> missing = validateAndGetMissingTxnIds(aiJson, expectedIds);

            // Retry ONCE if AI missed ids (common + fixable)
            if (!missing.isEmpty()) {
                String repairPrompt = buildRepairPrompt(llmText, missing, expectedIds);
                String repairedText = callAnthropic(repairPrompt);
                aiJson = parseJsonObject(repairedText);

                missing = validateAndGetMissingTxnIds(aiJson, expectedIds);
                if (!missing.isEmpty()) {
                    throw new IllegalArgumentException(
                            "AI did not assign all transactions exactly once (chunk " + (i + 1) + "). Missing ids: " + missing
                    );
                }
            }

            // Collect categories per txnId from this chunk
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> categories = (List<Map<String, Object>>) aiJson.get("categories");
            if (categories == null) categories = new ArrayList<>();

            for (Map<String, Object> c : categories) {
                String cat = String.valueOf(c.get("category"));
                List<Integer> txnIds = toIntList(c.get("txnIds"));
                if (txnIds == null) continue;

                for (Integer tid : txnIds) {
                    if (tid == null) continue;
                    if (txnIdToCategory.containsKey(tid)) {
                        throw new IllegalArgumentException("AI duplicated transaction id across chunks: " + tid);
                    }
                    txnIdToCategory.put(tid, cat);
                }
            }
        }

        // Ensure every txnId 1..expectedTxnCount got categorized
        List<Integer> missingOverall = new ArrayList<>();
        for (int tid = 1; tid <= expectedTxnCount; tid++) {
            if (!txnIdToCategory.containsKey(tid)) missingOverall.add(tid);
        }
        if (!missingOverall.isEmpty()) {
            throw new IllegalArgumentException("AI did not assign all transactions exactly once. Missing ids: " + missingOverall);
        }

        Map<Integer, Item> idToItem = buildIdToItem(items);

        Map<String, LinkedHashSet<Integer>> catToIds = new HashMap<>();
        for (int tid = 1; tid <= expectedTxnCount; tid++) {
            Item it = idToItem.get(tid);
            if (it == null) continue;

            String cat = txnIdToCategory.get(tid);
            if (cat == null || cat.isBlank()) cat = "Other";

            if ("refund".equals(it.kind())) cat = "Refunds";

            catToIds.computeIfAbsent(cat, k -> new LinkedHashSet<>()).add(tid);
        }

        List<Map<String, Object>> rebuiltCategories = new ArrayList<>();
        for (Map.Entry<String, LinkedHashSet<Integer>> e : catToIds.entrySet()) {
            String cat = e.getKey();
            List<Integer> txnIds = new ArrayList<>(e.getValue());
            if (txnIds.isEmpty()) continue;

            BigDecimal total = BigDecimal.ZERO;

            Map<String, BigDecimal> merchantTotals = new HashMap<>();
            for (Integer tid : txnIds) {
                Item it = idToItem.get(tid);
                if (it == null) continue;

                total = total.add(it.amount());
                merchantTotals.merge(it.merchant(), it.amount(), BigDecimal::add);
            }

            List<Map<String, Object>> merchants = merchantTotals.entrySet().stream()
                    .sorted((a, b) -> b.getValue().compareTo(a.getValue()))
                    .limit(5)
                    .map(me -> {
                        Map<String, Object> mm = new HashMap<>();
                        mm.put("merchant", me.getKey());
                        mm.put("amount", me.getValue());
                        return mm;
                    })
                    .toList();

            Map<String, Object> catObj = new HashMap<>();
            catObj.put("category", cat);
            catObj.put("total", total);
            catObj.put("txnIds", txnIds);
            catObj.put("merchants", merchants);
            rebuiltCategories.add(catObj);
        }

        rebuiltCategories.sort((a, b) -> {
            BigDecimal ta = toBigDecimal(a.get("total"));
            BigDecimal tb = toBigDecimal(b.get("total"));
            return tb.compareTo(ta);
        });

        Map<String, Object> aiJsonOut = new HashMap<>();
        aiJsonOut.put("categories", rebuiltCategories);

        BigDecimal grossSpend = items.stream()
                .filter(m -> "expense".equals(String.valueOf(m.get("kind"))))
                .map(m -> toBigDecimal(m.get("amount")))
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal refundsTotal = items.stream()
                .filter(m -> "refund".equals(String.valueOf(m.get("kind"))))
                .map(m -> toBigDecimal(m.get("amount")))
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal netSpend = grossSpend.subtract(refundsTotal);

        aiJsonOut.put("totalExpenses", netSpend);
        aiJsonOut.put("grossSpend", grossSpend);
        aiJsonOut.put("refundsTotal", refundsTotal);

        aiJsonOut.put("billPaymentsTotal", billPaymentsTotal);
        aiJsonOut.put("payrollTotal", payrollTotal); // NEW

        Map<String, Object> summaryForInsights = buildInsightsSummary(
                items, rebuiltCategories, grossSpend, refundsTotal, netSpend, billPaymentsTotal, payrollTotal, minDate, maxDate
        );

        String insightsPrompt = buildInsightsPrompt(summaryForInsights);
        String insightsText = callAnthropic(insightsPrompt);
        Map<String, Object> insightsJson = parseJsonObject(insightsText);
        aiJsonOut.put("insights", insightsJson);

        Map<String, Object> result = new HashMap<>();
        result.put("ok", true);
        result.put("filename", buildFilenameLabel(files));
        result.put("transactionCount", items.size());
        result.put("ai", aiJsonOut);
        return result;
    }

    // ---------------- Prompt / LLM ----------------

    private String buildPrompt(List<Map<String, Object>> items) {
        String txnsJson;
        try {
            txnsJson = objectMapper.writeValueAsString(items);
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to serialize transactions for AI prompt.", e);
        }

        return """
You are a financial categorization engine.

Task:
Given transactions, assign each transaction id to exactly one category.

Each transaction has:
- id: integer
- date: string
- merchant: string
- amount: number (always positive magnitude)
- kind: "expense" or "refund"

Return STRICT JSON ONLY. No markdown. No commentary.

Allowed categories (use these exact strings):
["Subscriptions","Bills","Dining","Groceries","Transport","Shopping","Health","Travel","Entertainment","Fees","Refunds","Other"]

Rules (MUST follow all):
- Use only the allowed categories.
- CRITICAL: Each provided transaction id must appear in EXACTLY ONE category. Never duplicate ids.
- If kind == "refund", category MUST be "Refunds".
- If kind == "expense", choose the best category based on merchant.
- Return top-level schema exactly.

Output schema (JSON):
{
  "categories": [
    {
      "category": string,
      "txnIds": [number]
    }
  ]
}

Transactions JSON:
""" + txnsJson;
    }

    private String buildInsightsPrompt(Map<String, Object> summary) {
        String json;
        try {
            json = objectMapper.writeValueAsString(summary);
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to serialize summary for insights prompt.", e);
        }

        return """
You are a financial behavior analyst.

Generate insights based ONLY on the provided summary JSON.
Do NOT narrate individual transactions.
Do NOT invent context like vacations, locations, or reasons for spending.

Focus on:
- Where the most money is going (top categories)
- Which merchants receive the most money
- Spend concentration (percentages)
- Recurring behavior (merchant frequency)
- Practical optimization ideas
- Anomalies only if clearly indicated by the summary

Return STRICT JSON ONLY (no markdown, no commentary).

Output schema:
{
  "highlights": [string],
  "topSpendingCategory": string,
  "topMerchant": string,
  "concentrationNotes": [string],
  "optimizationIdeas": [string],
  "anomalies": [string]
}

Summary JSON:
""" + json;
    }

    private Map<String, Object> buildInsightsSummary(
            List<Map<String, Object>> items,
            List<Map<String, Object>> rebuiltCategories,
            BigDecimal grossSpend,
            BigDecimal refundsTotal,
            BigDecimal netSpend,
            BigDecimal billPaymentsTotal,
            BigDecimal payrollTotal,
            LocalDate minDate,
            LocalDate maxDate
    ) {
        Map<String, Object> out = new HashMap<>();

        out.put("periodStart", minDate == null ? null : minDate.toString());
        out.put("periodEnd", maxDate == null ? null : maxDate.toString());

        out.put("transactionCount", items.size());
        out.put("grossSpend", grossSpend);
        out.put("refundsTotal", refundsTotal);
        out.put("netSpend", netSpend);
        out.put("billPaymentsTotal", billPaymentsTotal);
        out.put("payrollTotal", payrollTotal);

        Map<String, BigDecimal> merchantTotals = new HashMap<>();
        Map<String, Integer> merchantCounts = new HashMap<>();

        for (Map<String, Object> m : items) {
            String kind = String.valueOf(m.get("kind"));
            if (!"expense".equals(kind)) continue;

            String merchant = String.valueOf(m.get("merchant"));
            BigDecimal amt = toBigDecimal(m.get("amount"));

            merchantTotals.merge(merchant, amt, BigDecimal::add);
            merchantCounts.merge(merchant, 1, Integer::sum);
        }

        List<Map<String, Object>> topMerchants = merchantTotals.entrySet().stream()
                .sorted((a, b) -> b.getValue().compareTo(a.getValue()))
                .limit(10)
                .map(e -> {
                    Map<String, Object> mm = new HashMap<>();
                    mm.put("merchant", e.getKey());
                    mm.put("total", e.getValue());
                    mm.put("count", merchantCounts.getOrDefault(e.getKey(), 0));
                    return mm;
                })
                .toList();

        out.put("topMerchants", topMerchants);

        List<Map<String, Object>> topCategories = rebuiltCategories.stream()
                .filter(c -> !"Refunds".equals(String.valueOf(c.get("category"))))
                .filter(c -> toBigDecimal(c.get("total")).compareTo(BigDecimal.ZERO) > 0)
                .limit(10)
                .map(c -> {
                    String cat = String.valueOf(c.get("category"));
                    BigDecimal total = toBigDecimal(c.get("total"));

                    BigDecimal pct = BigDecimal.ZERO;
                    if (grossSpend != null && grossSpend.compareTo(BigDecimal.ZERO) > 0) {
                        pct = total.multiply(new BigDecimal("100"))
                                .divide(grossSpend, 1, java.math.RoundingMode.HALF_UP);
                    }

                    Map<String, Object> cc = new HashMap<>();
                    cc.put("category", cat);
                    cc.put("total", total);
                    cc.put("percentageOfGrossSpend", pct);
                    cc.put("topMerchants", c.get("merchants"));
                    return cc;
                })
                .toList();

        out.put("topCategories", topCategories);

        BigDecimal top3 = BigDecimal.ZERO;
        for (int i = 0; i < Math.min(3, topCategories.size()); i++) {
            top3 = top3.add(toBigDecimal(topCategories.get(i).get("total")));
        }
        BigDecimal top3Pct = BigDecimal.ZERO;
        if (grossSpend != null && grossSpend.compareTo(BigDecimal.ZERO) > 0) {
            top3Pct = top3.multiply(new BigDecimal("100"))
                    .divide(grossSpend, 1, java.math.RoundingMode.HALF_UP);
        }
        out.put("top3CategoriesTotal", top3);
        out.put("top3CategoriesPctOfGrossSpend", top3Pct);

        return out;
    }

    private String callAnthropic(String prompt) {
        String apiKey = System.getenv("ANTHROPIC_API_KEY");
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalArgumentException("Missing ANTHROPIC_API_KEY env var.");
        }

        String model = System.getenv("ANTHROPIC_MODEL");
        if (model == null || model.isBlank()) {
            model = "claude-3-5-sonnet-20240620";
        }

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

        if (resp == null) {
            throw new IllegalArgumentException("Unexpected Anthropic response: empty body.");
        }

        Object contentObj = resp.get("content");
        if (!(contentObj instanceof List<?> contentList) || contentList.isEmpty()) {
            throw new IllegalArgumentException("Unexpected Anthropic response: missing content.");
        }

        Object first = contentList.get(0);
        if (!(first instanceof Map<?, ?> firstMap)) {
            throw new IllegalArgumentException("Unexpected Anthropic response: invalid content format.");
        }

        Object textObj = firstMap.get("text");
        if (!(textObj instanceof String text) || text.isBlank()) {
            throw new IllegalArgumentException("Unexpected Anthropic response: empty text.");
        }

        return text.trim();
    }

    private Map<String, Object> parseJsonObject(String raw) {
        String s = raw.trim();

        if (s.startsWith("```")) {
            s = s.replaceAll("^```[a-zA-Z]*\\s*", "");
            s = s.replaceAll("\\s*```$", "");
            s = s.trim();
        }

        try {
            return objectMapper.readValue(s, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            throw new IllegalArgumentException(
                    "AI did not return valid JSON. Raw AI output:\n" + raw, e
            );
        }
    }

    @SuppressWarnings("unchecked")
    private List<Integer> validateAndGetMissingTxnIds(Map<String, Object> aiJson, Set<Integer> expectedIds) {
        Object catsObj = aiJson.get("categories");
        if (!(catsObj instanceof List<?> cats)) {
            throw new IllegalArgumentException("AI JSON missing categories array.");
        }

        Set<Integer> seen = new HashSet<>();

        for (Object cObj : cats) {
            if (!(cObj instanceof Map<?, ?> cMap)) continue;

            Object txnIdsObj = cMap.get("txnIds");
            if (!(txnIdsObj instanceof List<?> txnIds)) {
                throw new IllegalArgumentException("AI JSON missing txnIds in a category (required).");
            }

            for (Object idObj : txnIds) {
                if (idObj == null) continue;

                int tid = (idObj instanceof Number n)
                        ? n.intValue()
                        : Integer.parseInt(String.valueOf(idObj));

                if (!expectedIds.contains(tid)) {
                    throw new IllegalArgumentException("AI returned txnId not in this request chunk: " + tid);
                }

                if (!seen.add(tid)) {
                    throw new IllegalArgumentException("AI duplicated transaction id across categories: " + tid);
                }
            }
        }

        List<Integer> missing = new ArrayList<>();
        for (Integer i : expectedIds) {
            if (!seen.contains(i)) missing.add(i);
        }
        missing.sort(Comparator.naturalOrder());
        return missing;
    }

    private String buildRepairPrompt(String previousJson, List<Integer> missingIds, Set<Integer> expectedIds) {
        return """
You returned JSON but it is invalid because some transaction ids were not assigned.

Fix it and return STRICT JSON ONLY. Keep the same schema.

CRITICAL rules:
- Every provided transaction id must appear in EXACTLY ONE category.
- Do not duplicate any ids.
- Refunds (kind=="refund") MUST be in category "Refunds".
- Use only allowed categories.

Provided transaction ids (scope): %s
Missing ids that MUST be assigned: %s

Here is your previous JSON (repair it):
%s
""".formatted(expectedIds.toString(), missingIds.toString(), previousJson);
    }

    // ---------------- Deterministic helpers ----------------

    private Map<Integer, Item> buildIdToItem(List<Map<String, Object>> items) {
        Map<Integer, Item> out = new HashMap<>();
        for (Map<String, Object> m : items) {
            int id = toInt(m.get("id"));
            String merchant = String.valueOf(m.get("merchant"));
            BigDecimal amount = toBigDecimal(m.get("amount"));
            String kind = String.valueOf(m.get("kind"));
            out.put(id, new Item(merchant, amount, kind));
        }
        return out;
    }

    private List<Integer> toIntList(Object o) {
        if (!(o instanceof List<?> list)) return null;
        List<Integer> out = new ArrayList<>();
        for (Object x : list) {
            if (x == null) continue;
            out.add((x instanceof Number n) ? n.intValue() : Integer.parseInt(String.valueOf(x)));
        }
        return out;
    }

    private int toInt(Object o) {
        if (o instanceof Number n) return n.intValue();
        return Integer.parseInt(String.valueOf(o));
    }

    private BigDecimal toBigDecimal(Object o) {
        if (o == null) return BigDecimal.ZERO;
        if (o instanceof BigDecimal bd) return bd;
        return new BigDecimal(String.valueOf(o));
    }

    private record Item(String merchant, BigDecimal amount, String kind) {}

    private boolean isPayment(String desc) {
        if (desc == null) return false;
        String s = desc.toLowerCase(Locale.ROOT);
        return s.contains("payment") || s.contains("thank you") || s.contains("autopay");
    }

    // NEW
    private boolean isPayroll(String desc) {
        if (desc == null) return false;
        String s = desc.toLowerCase(Locale.ROOT);
        return s.contains("payroll")
                || s.contains("salary")
                || s.contains("direct deposit")
                || s.contains("paycheck")
                || s.contains("ach credit")
                || s.contains("employer");
    }

    private String buildFilenameLabel(List<MultipartFile> files) {
        if (files == null || files.isEmpty()) return "";
        List<String> names = files.stream()
                .filter(Objects::nonNull)
                .map(MultipartFile::getOriginalFilename)
                .filter(Objects::nonNull)
                .filter(s -> !s.isBlank())
                .toList();
        if (names.isEmpty()) return "uploaded files";
        if (names.size() == 1) return names.get(0);
        if (names.size() <= 3) return String.join(", ", names);
        return names.size() + " files";
    }

    private List<List<Map<String, Object>>> chunkItems(List<Map<String, Object>> items, int chunkSize) {
        List<List<Map<String, Object>>> chunks = new ArrayList<>();
        for (int i = 0; i < items.size(); i += chunkSize) {
            int end = Math.min(i + chunkSize, items.size());
            chunks.add(items.subList(i, end));
        }
        return chunks;
    }

    // ---------------- CSV parsing (bank-agnostic) ----------------

    private List<Txn> parseCsv(List<MultipartFile> files) {
        List<Txn> all = new ArrayList<>();

        for (MultipartFile file : files) {
            if (file == null || file.isEmpty()) continue;
            all.addAll(parseCsvSingle(file));
        }

        all.sort(Comparator.comparing(Txn::date).thenComparing(Txn::description));

        LinkedHashMap<String, Txn> dedup = new LinkedHashMap<>();
        for (Txn t : all) {
            String key = t.date() + "|" + t.amount() + "|" + normalizeMerchant(t.description()).toLowerCase(Locale.ROOT);
            dedup.putIfAbsent(key, t);
        }

        return new ArrayList<>(dedup.values());
    }

    private List<Txn> parseCsvSingle(MultipartFile file) {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(file.getInputStream(), StandardCharsets.UTF_8))) {

            CSVParser parser = CSVFormat.DEFAULT
                    .builder()
                    .setHeader()
                    .setSkipHeaderRecord(true)
                    .setIgnoreHeaderCase(true)
                    .setTrim(true)
                    .build()
                    .parse(reader);

            Map<String, Integer> headerMap = parser.getHeaderMap();

            String dateCol = findColumn(headerMap.keySet(), List.of(
                    "date", "posted date", "posting date", "transaction date", "trans date"
            ));

            String descCol = findColumn(headerMap.keySet(), List.of(
                    "description", "transaction description", "original description",
                    "merchant", "merchant name",
                    "payee", "payee name",
                    "vendor", "vendor name",
                    "narrative", "memo", "details"
            ));

            String amountCol = findColumn(headerMap.keySet(), List.of("amount", "amt", "value"));

            String debitCol = findColumnOptional(headerMap.keySet(), List.of("debit", "withdrawal", "outflow"));
            String creditCol = findColumnOptional(headerMap.keySet(), List.of("credit", "deposit", "inflow"));

            if (dateCol == null) throw new IllegalArgumentException("CSV is missing a recognizable date column.");
            if (amountCol == null && (debitCol == null && creditCol == null)) {
                throw new IllegalArgumentException("CSV is missing a recognizable amount column (or debit/credit columns).");
            }
            if (descCol == null) descCol = "";

            List<Txn> out = new ArrayList<>();
            for (CSVRecord r : parser) {
                String dateRaw = safeGet(r, dateCol);
                if (dateRaw == null || dateRaw.isBlank()) continue;

                LocalDate date = parseDate(dateRaw);
                String desc = descCol.isEmpty() ? "" : Optional.ofNullable(safeGet(r, descCol)).orElse("");

                BigDecimal amount;
                if (amountCol != null) {
                    amount = parseMoney(safeGet(r, amountCol));
                } else {
                    BigDecimal debit = parseMoneyOrZero(safeGet(r, debitCol));
                    BigDecimal credit = parseMoneyOrZero(safeGet(r, creditCol));
                    amount = credit.subtract(debit);
                }

                if (amount == null) continue;
                out.add(new Txn(date, desc, amount));
            }

            return out;
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to parse CSV: " + e.getMessage(), e);
        }
    }

    private String safeGet(CSVRecord r, String col) {
        if (col == null) return null;
        try { return r.get(col); } catch (Exception ignored) { return null; }
    }

    private String findColumn(Set<String> headers, List<String> candidates) {
        String best = null;
        int bestScore = 0;

        for (String h : headers) {
            String hn = normalize(h);

            for (String c : candidates) {
                String cn = normalize(c);

                int score = 0;
                if (hn.equals(cn)) score += 100;
                if (hn.contains(cn)) score += 60;
                if (cn.contains(hn)) score += 40;
                if (hn.replace(" ", "").equals(cn.replace(" ", ""))) score += 80;
                if (hn.startsWith(cn)) score += 20;

                if (score > bestScore) {
                    bestScore = score;
                    best = h;
                }
            }
        }
        return bestScore == 0 ? null : best;
    }

    private String findColumnOptional(Set<String> headers, List<String> candidates) {
        return findColumn(headers, candidates);
    }

    private String normalize(String s) {
        return s == null ? "" : s.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
    }

    private LocalDate parseDate(String raw) {
        String v = raw.trim();
        for (DateTimeFormatter f : DATE_FORMATS) {
            try { return LocalDate.parse(v, f); } catch (Exception ignored) {}
        }
        int t = v.indexOf('T');
        if (t > 0) return parseDate(v.substring(0, t));
        throw new IllegalArgumentException("Unrecognized date format: " + raw);
    }

    private BigDecimal parseMoney(String raw) {
        if (raw == null) return null;
        String v = raw.trim();
        if (v.isBlank()) return null;

        boolean parensNegative = v.startsWith("(") && v.endsWith(")");
        v = v.replace("(", "").replace(")", "");
        v = NON_NUMERIC.matcher(v).replaceAll("");

        if (v.isBlank() || v.equals("-")) return null;

        BigDecimal amt = new BigDecimal(v);
        if (parensNegative) amt = amt.negate();
        return amt;
    }

    private BigDecimal parseMoneyOrZero(String raw) {
        BigDecimal v = parseMoney(raw);
        return v == null ? BigDecimal.ZERO : v.abs();
    }

    private String normalizeMerchant(String desc) {
        if (desc == null) return "Unknown";
        String s = desc.trim();
        if (s.isBlank()) return "Unknown";

        s = s.replaceAll("\\s+", " ");
        s = s.replaceAll("\\s+#\\d+.*$", "");
        s = s.replaceAll("\\s+\\d{4,}.*$", "");
        if (s.length() > 60) s = s.substring(0, 60);

        return s;
    }

    private record Txn(LocalDate date, String description, BigDecimal amount) {}
}
