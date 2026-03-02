package com.ai.platform.api.finance;

import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.*;

@Service
public class TransactionsService {

    private final CsvTransactionParser csvParser;
    private final AiCategorizationService categorizationService;
    private final AiInsightsService insightsService;

    private static final int MAX_TOTAL_ITEMS = 2000;

    public TransactionsService(
            CsvTransactionParser csvParser,
            AiCategorizationService categorizationService,
            AiInsightsService insightsService
    ) {
        this.csvParser = csvParser;
        this.categorizationService = categorizationService;
        this.insightsService = insightsService;
    }

    // ---------------- Month filtering ----------------

    private static YearMonth parseMonthKey(String monthKey) {
        return YearMonth.parse(monthKey);
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

    // ---------------- MAIN ENTRY ----------------

    public Map<String, Object> aiAnalyze(List<MultipartFile> files, String monthKey) {

        BigDecimal billPaymentsTotal = BigDecimal.ZERO;
        BigDecimal payrollTotal = BigDecimal.ZERO;
		BigDecimal transfersTotal = BigDecimal.ZERO;
		BigDecimal investmentsTotal = BigDecimal.ZERO;
		int billPaymentCount = 0;

        List<Txn> txnsAll = csvParser.parseCsv(files);
        List<Txn> txns = filterTxnsToMonth(txnsAll, monthKey);
        LocalDate minDate = null;
        LocalDate maxDate = null;

        List<Map<String, Object>> items = new ArrayList<>();
        int id = 0;

        for (Txn t : txns) {

            String desc = t.description();
            BigDecimal amt = t.amount();
			
            if (amt == null || amt.compareTo(BigDecimal.ZERO) == 0) continue;

            if (amt.compareTo(BigDecimal.ZERO) > 0 && isPayroll(desc)) {
                payrollTotal = payrollTotal.add(amt);
                continue;
            }
			if (isTransfer(desc)) {
				transfersTotal = transfersTotal.add(amt.abs());
				continue;
			}
			if (isInvestment(desc)) {
				investmentsTotal = investmentsTotal.add(amt.abs());
				continue;
			}
            if (isPayment(desc)) {
				billPaymentCount++;
				///////// Debug start
			/*	System.out.println("==== BILL PAYMENT MATCH ====");
    System.out.println("Date: " + t.date());
    System.out.println("Description: " + desc);
    System.out.println("Raw Amount: " + amt);
    System.out.println("Abs Added To Total: " + amt.abs());
    System.out.println("============================");*/
				//////// Debug end
                billPaymentsTotal = billPaymentsTotal.add(amt.abs());
                continue;
            }

            if (minDate == null || t.date().isBefore(minDate)) minDate = t.date();
            if (maxDate == null || t.date().isAfter(maxDate)) maxDate = t.date();

            String kind = amt.compareTo(BigDecimal.ZERO) < 0 ? "expense" : "refund";
            BigDecimal value = amt.abs();

            Map<String, Object> m = new HashMap<>();
            m.put("id", ++id);
            m.put("date", t.date().toString());
            m.put("merchant", desc);
            m.put("amount", value);
            m.put("kind", kind);

            items.add(m);

            if (items.size() >= MAX_TOTAL_ITEMS) break;
        }
/////// Debug start
/*System.out.println("######## BILL PAYMENT FINAL TOTAL ########");
System.out.println("Bill payment transactions counted: " + billPaymentCount);
System.out.println("billPaymentsTotal = " + billPaymentsTotal);
System.out.println("##########################################");*/
////// Debug end
        if (items.isEmpty()) {
            throw new IllegalArgumentException("No usable transactions found.");
        }
/////////////////////Debug Start
/*System.out.println("==== ITEMS BEING SENT TO AI FOR CATEGORIZATION ====");

for (Map<String, Object> item : items) {
    System.out.println(
        "ID: " + item.get("id") +
        " | Date: " + item.get("date") +
        " | Merchant: " + item.get("merchant") +
        " | Amount: " + item.get("amount") +
        " | Kind: " + item.get("kind")
    );
}

System.out.println("Total items sent to AI: " + items.size());
System.out.println("==== END OF ITEMS SENT TO AI ====");*/

////////////////////Debug End
        // ✅ Real AI categorization restored
        Map<Integer, String> txnIdToCategory =
                categorizationService.categorize(items);

        Map<Integer, Item> idToItem = buildIdToItem(items);

        Map<String, LinkedHashSet<Integer>> catToIds = new HashMap<>();
        BigDecimal grossSpend = BigDecimal.ZERO;
        BigDecimal refundsTotal = BigDecimal.ZERO;

        for (int tid = 1; tid <= items.size(); tid++) {

            Item it = idToItem.get(tid);
            if (it == null) continue;

            String cat = txnIdToCategory.getOrDefault(tid, "Other");

            if ("refund".equals(it.kind())) cat = "Refunds";

            catToIds.computeIfAbsent(cat, k -> new LinkedHashSet<>()).add(tid);

            if ("expense".equals(it.kind())) grossSpend = grossSpend.add(it.amount());
            if ("refund".equals(it.kind())) refundsTotal = refundsTotal.add(it.amount());
        }

        List<Map<String, Object>> categoriesOut = new ArrayList<>();

        for (Map.Entry<String, LinkedHashSet<Integer>> entry : catToIds.entrySet()) {

            String cat = entry.getKey();
            List<Integer> txnIds = new ArrayList<>(entry.getValue());

            BigDecimal total = BigDecimal.ZERO;
            Map<String, BigDecimal> merchantTotals = new HashMap<>();

            for (Integer tid : txnIds) {
                Item it = idToItem.get(tid);
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

            categoriesOut.add(catObj);
        }

        categoriesOut.sort((a, b) ->
                ((BigDecimal) b.get("total")).compareTo((BigDecimal) a.get("total"))
        );

        BigDecimal netSpend = grossSpend.subtract(refundsTotal);
		BigDecimal netCashFlow = payrollTotal.subtract(netSpend);
		

        Map<String, Object> aiOut = new HashMap<>();
        aiOut.put("categories", categoriesOut);
        aiOut.put("totalExpenses", netSpend);
        aiOut.put("grossSpend", grossSpend);
        aiOut.put("refundsTotal", refundsTotal);
        aiOut.put("billPaymentsTotal", billPaymentsTotal);
        aiOut.put("payrollTotal", payrollTotal);
		aiOut.put("netCashFlow", netCashFlow);
		aiOut.put("investmentsTotal", investmentsTotal);
		aiOut.put("transfersTotal", transfersTotal);
        // ✅ Real AI insights restored
        Map<String, Object> insights =
                insightsService.generateInsights(aiOut);

        aiOut.put("insights", insights);

        Map<String, Object> result = new HashMap<>();
        result.put("ok", true);
        result.put("filename", buildFilenameLabel(files));
        result.put("transactionCount", items.size());
        result.put("ai", aiOut);

        return result;
    }

public Map<String, Object> regenerateInsights(Map<String, Object> payload) {

    List<Map<String, Object>> categories =
            (List<Map<String, Object>>) payload.get("categories");

    if (categories == null || categories.isEmpty()) {
        throw new IllegalArgumentException("No categories provided.");
    }

    BigDecimal payrollTotal = new BigDecimal(
            String.valueOf(payload.getOrDefault("payrollTotal", "0"))
    );

    BigDecimal grossSpend = BigDecimal.ZERO;
    BigDecimal refundsTotal = BigDecimal.ZERO;
    BigDecimal investmentsTotal = BigDecimal.ZERO;

    List<Map<String, Object>> rebuiltCategories = new ArrayList<>();

    for (Map<String, Object> cat : categories) {

        String categoryName = String.valueOf(cat.get("category"));
        List<Map<String, Object>> merchants =
                (List<Map<String, Object>>) cat.get("merchants");

        BigDecimal categoryTotal = BigDecimal.ZERO;

        if (merchants != null) {
            for (Map<String, Object> m : merchants) {
                BigDecimal amt = new BigDecimal(String.valueOf(m.get("amount")));
                categoryTotal = categoryTotal.add(amt);
            }
        }

        // 🚀 Investments excluded from spending
        if ("Investments".equalsIgnoreCase(categoryName)) {
            investmentsTotal = investmentsTotal.add(categoryTotal);
            continue;
        }

        grossSpend = grossSpend.add(categoryTotal);

        Map<String, Object> rebuilt = new HashMap<>();
        rebuilt.put("category", categoryName);
        rebuilt.put("total", categoryTotal);
        rebuilt.put("merchants", merchants);

        rebuiltCategories.add(rebuilt);
    }

    BigDecimal totalExpenses = grossSpend.subtract(refundsTotal);
    BigDecimal netCashFlow = payrollTotal.subtract(totalExpenses);
	BigDecimal transfersTotal = new BigDecimal(String.valueOf(payload.getOrDefault("transfersTotal", "0")));

    Map<String, Object> aiOut = new HashMap<>();
    aiOut.put("categories", rebuiltCategories);
    aiOut.put("grossSpend", grossSpend);
    aiOut.put("refundsTotal", refundsTotal);
    aiOut.put("totalExpenses", totalExpenses);
    aiOut.put("investmentsTotal", investmentsTotal);
	aiOut.put("transfersTotal", transfersTotal);
    aiOut.put("payrollTotal", payrollTotal);
    aiOut.put("netCashFlow", netCashFlow);

    Map<String, Object> insights =
            insightsService.generateInsights(aiOut);

    aiOut.put("insights", insights);

    Map<String, Object> result = new HashMap<>();
result.put("ok", true);
result.put("ai", aiOut);
return result;
}
    // ---------------- Helpers ----------------

    private Map<Integer, Item> buildIdToItem(List<Map<String, Object>> items) {
        Map<Integer, Item> out = new HashMap<>();
        for (Map<String, Object> m : items) {
            int id = (Integer) m.get("id");
            String merchant = String.valueOf(m.get("merchant"));
            BigDecimal amount = new BigDecimal(String.valueOf(m.get("amount")));
            String kind = String.valueOf(m.get("kind"));
            out.put(id, new Item(merchant, amount, kind));
        }
        return out;
    }

private boolean isPayment(String desc) {
    if (desc == null) return false;
    String s = desc.toLowerCase(Locale.ROOT);

    return s.contains("credit card payment")
        || s.contains("payment - thank you")
        || s.contains("online banking transfer")
        || s.contains("transfer to")
        || s.contains("transfer from")
        || s.contains("thank you for your payment");
}

    private boolean isPayroll(String desc) {
        if (desc == null) return false;
        String s = desc.toLowerCase(Locale.ROOT);
        return s.contains("payroll")
                || s.contains("salary")
                || s.contains("direct deposit")
				|| s.contains("direct dep") 
                || s.contains("paycheck")
                || s.contains("ach credit")
                || s.contains("employer");
    }
private boolean isTransfer(String desc) {
    if (desc == null) return false;
    String s = desc.toLowerCase(Locale.ROOT);

    // explicit transfers
    if (s.contains("transfer to") || s.contains("transfer from")) return true;
    if (s.contains("online banking transfer")) return true;

    // generic card settlement signals
    if (s.contains("credit card")) return true;
    if (s.contains("card payment")) return true;
    if (s.contains("cc payment")) return true;
    if (s.contains("payment - thank you")) return true;
    if (s.contains("autopay") && s.contains("card")) return true;
    // ACH PMT to known card issuers (covers AMEX case)
    if (s.contains("ach pmt") &&
        (s.contains("express") || 
         s.contains("amex") || 
         s.contains("chase") || 
         s.contains("citi") || 
         s.contains("capital one") ||
         s.contains("discover"))) {
        return true;
    }

    return false;
}
private boolean isInvestment(String desc) {
    if (desc == null) return false;
    String s = desc.toLowerCase(Locale.ROOT);

    if (s.contains("vanguard")) return true;
    if (s.contains("fidelity")) return true;
    if (s.contains("robinhood")) return true;
    if (s.contains("schwab")) return true;
    if (s.contains("brokerage")) return true;
    if (s.contains("ira")) return true;
    if (s.contains("401k")) return true;
    if (s.contains("trader funding")) return true;

    return false;
}
    private String buildFilenameLabel(List<MultipartFile> files) {
        if (files == null || files.isEmpty()) return "";
        List<String> names = files.stream()
                .map(MultipartFile::getOriginalFilename)
                .filter(Objects::nonNull)
                .toList();
        if (names.size() == 1) return names.get(0);
        return names.size() + " files";
    }

    private record Item(String merchant, BigDecimal amount, String kind) {}
}