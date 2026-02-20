package com.ai.platform.api.finance;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.Pattern;
import com.ai.platform.api.finance.Txn;


@Component
public class CsvTransactionParser {

    private static final List<DateTimeFormatter> DATE_FORMATS = List.of(
            DateTimeFormatter.ISO_LOCAL_DATE,
            DateTimeFormatter.ofPattern("M/d/uuuu"),
            DateTimeFormatter.ofPattern("MM/dd/uuuu"),
            DateTimeFormatter.ofPattern("uuuu-M-d"),
            DateTimeFormatter.ofPattern("uuuu/MM/dd")
    );

    private static final Pattern NON_NUMERIC = Pattern.compile("[^0-9.\\-]");

    public List<Txn> parseCsv(List<MultipartFile> files) {
        List<Txn> all = new ArrayList<>();

        for (MultipartFile file : files) {
            if (file == null || file.isEmpty()) continue;
            all.addAll(parseCsvSingle(file));
        }

        all.sort(Comparator.comparing(Txn::date)
                .thenComparing(Txn::description));

        LinkedHashMap<String, Txn> dedup = new LinkedHashMap<>();
        for (Txn t : all) {
            String key = t.date() + "|" + t.amount() + "|" +
                    normalizeMerchant(t.description()).toLowerCase(Locale.ROOT);
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

            if (dateCol == null) throw new IllegalArgumentException("CSV missing date column.");
            if (amountCol == null && debitCol == null && creditCol == null)
                throw new IllegalArgumentException("CSV missing amount column.");

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
}
