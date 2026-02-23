import React, { useMemo, useRef, useState, useEffect } from "react";
import { useAuth } from "@clerk/clerk-react";

type AiMerchant = {
  merchant: string;
  amount: number;
};

type AiCategory = {
  category: string;
  total: number;
  merchants: AiMerchant[];
  txnIds?: number[];
};

type AiInsights = {
  highlights?: string[];
  topSpendingCategory?: string;
  topMerchant?: string;
  concentrationNotes?: string[];
  optimizationIdeas?: string[];
  anomalies?: string[];
};

type AiResult = {
  totalExpenses: number;
  billPaymentsTotal?: number;
  payrollTotal?: number; // NEW
  categories: AiCategory[];
  notes?: string;
  insights?: AiInsights;
};

type UploadAiResponse = {
  ok: boolean;
  filename: string;
  transactionCount: number;
  ai: AiResult;
  availableCategories?: string[];
};


export default function SpendingIntelligencePage() {
  const { getToken } = useAuth();

  const [files, setFiles] = useState<File[]>([]);
  const inputRef = useRef<HTMLInputElement | null>(null);

  const [selectedMonth, setSelectedMonth] = useState<string>(() => {
    const now = new Date();
    const y = now.getFullYear();
    const m = String(now.getMonth() + 1).padStart(2, "0");
    return `${y}-${m}`;
  });

  const [loading, setLoading] = useState(false);
  const [message, setMessage] = useState<string | null>(null);
  const [result, setResult] = useState<UploadAiResponse | null>(null);

  // 🔹 Local categories (mutable)
  const [localCategories, setLocalCategories] = useState<AiCategory[]>([]);

  useEffect(() => {
    if (result?.ai?.categories) {
      setLocalCategories(result.ai.categories);
    }
  }, [result]);

  const monthOptions = useMemo(() => buildRecentMonthOptions(18), []);

  function addFiles(fileList: FileList | null) {
    if (!fileList || fileList.length === 0) return;

    const incoming = Array.from(fileList);

    setFiles((prev) => {
      const seen = new Set(prev.map((f) => `${f.name}|${f.size}|${f.lastModified}`));
      const merged = [...prev];

      for (const f of incoming) {
        const key = `${f.name}|${f.size}|${f.lastModified}`;
        if (!seen.has(key)) merged.push(f);
      }
      return merged;
    });

    if (inputRef.current) inputRef.current.value = "";
  }

  function removeFile(idx: number) {
    setFiles((prev) => prev.filter((_, i) => i !== idx));
  }

  function clearFiles() {
    setFiles([]);
    if (inputRef.current) inputRef.current.value = "";
  }

  async function handleUpload() {
    const API_BASE = import.meta.env.VITE_API_BASE_URL as string | undefined;

    if (!files || files.length === 0) {
      setMessage("Please select at least one file first.");
      return;
    }

    if (!API_BASE) {
      setMessage("Missing VITE_API_BASE_URL configuration.");
      return;
    }

    try {
      setLoading(true);
      setMessage(null);
      setResult(null);

      const token = await getToken();
      if (!token) {
        throw new Error("Authentication failed. Please sign in again.");
      }

      const formData = new FormData();
      files.forEach((f) => formData.append("files", f));
      formData.append("monthKey", selectedMonth); // NEW

      const response = await fetch(`${API_BASE}/api/transactions/upload`, {
        method: "POST",
        headers: {
          Authorization: `Bearer ${token}`,
        },
        body: formData,
      });

      const contentType = response.headers.get("content-type") || "";
      const isJson = contentType.includes("application/json");
      const data: any = isJson ? await response.json() : await response.text();

      if (!response.ok) {
        const errMsg =
          typeof data === "string" ? data : data?.error || JSON.stringify(data);
        throw new Error(errMsg || "Upload failed.");
      }

      setResult(data as UploadAiResponse);
	  console.log("UPLOAD RESPONSE:", data);
      clearFiles();
      setMessage(`File uploaded successfully. AI analysis complete for ${formatMonthLabel(selectedMonth)}.`);
    } catch (err: any) {
      setMessage(err?.message ?? "Unexpected error occurred.");
    } finally {
      setLoading(false);
    }
  }

  const hasAi =
    !!result?.ai &&
    Array.isArray(result.ai.categories) &&
    result.ai.categories.length > 0;

  const insights = result?.ai?.insights;

  const hasStructuredInsights =
    !!insights &&
    (hasAny(insights.highlights) ||
      !!insights.topSpendingCategory ||
      !!insights.topMerchant ||
      hasAny(insights.concentrationNotes) ||
      hasAny(insights.optimizationIdeas) ||
      hasAny(insights.anomalies));

  const hasLegacyNotes = !!result?.ai?.notes && result.ai.notes.trim().length > 0;

function toTitleCase(str: string) {
  return str
    .toLowerCase()
    .split(" ")
    .filter(Boolean)
    .map((word) => word.charAt(0).toUpperCase() + word.slice(1))
    .join(" ");
}

function moveMerchant(
  fromCategory: string,
  merchantName: string,
  amount: number,
  toCategory: string
) {
  // Normalize input
  const normalizedTo = toCategory.trim().toLowerCase();
  const normalizedFrom = fromCategory.trim().toLowerCase();

  if (normalizedFrom === normalizedTo) return;

  setLocalCategories((prev) => {
    const updated = prev.map((c) => ({
      ...c,
      merchants: [...(c.merchants || [])],
    }));

    // Case-insensitive source match
    const source = updated.find(
      (c) => c.category.trim().toLowerCase() === normalizedFrom
    );

    // Case-insensitive target match
    let target = updated.find(
      (c) => c.category.trim().toLowerCase() === normalizedTo
    );

    if (!source) return prev;

    const idx = source.merchants.findIndex(
      (m) => m.merchant === merchantName
    );

    if (idx === -1) return prev;

    const merchantObj = source.merchants[idx];

    // Remove from source
    source.merchants.splice(idx, 1);
    source.total = Number(source.total) - Number(amount);

    // If category exists (any casing), reuse it
    if (target) {
      target.merchants.push(merchantObj);
      target.total = Number(target.total) + Number(amount);
    } else {
      // Create new category using cleaned original casing
      const cleanedLabel = toTitleCase(toCategory.trim());

      updated.push({
        category: cleanedLabel,
        total: amount,
        merchants: [merchantObj],
      });
    }

    return updated.sort((a, b) => Number(b.total) - Number(a.total));
  });
}

  return (
    <div
      style={{
        maxWidth: 900,
        margin: "24px auto",
        fontFamily: "system-ui",
        padding: 12,
      }}
    >
      <h1 style={{ marginBottom: 4 }}>AI Spending Intelligence</h1>

      <div style={{ opacity: 0.8, marginBottom: 20, lineHeight: 1.5 }}>
        Upload your transaction export to generate an AI-powered breakdown of
        your spending — categorized totals, top merchants, and intelligent
        insights about where your money is going.
      </div>

      {/* Upload Section */}
      <div
        style={{
          border: "1px solid #e5e7eb",
          borderRadius: 12,
          padding: 18,
          marginBottom: 20,
        }}
      >
        <div style={{ fontWeight: 700, marginBottom: 10 }}>
          Upload Transaction File(s)
        </div>

        {/* NEW: month selector */}
        <div style={{ display: "flex", gap: 10, alignItems: "center", marginBottom: 10, flexWrap: "wrap" }}>
          <div style={{ fontSize: 13, fontWeight: 800 }}>Analyze month</div>
          <select
            value={selectedMonth}
            onChange={(e) => setSelectedMonth(e.target.value)}
            disabled={loading}
            style={{
              padding: "8px 10px",
              borderRadius: 10,
              border: "1px solid #e5e7eb",
              cursor: loading ? "not-allowed" : "pointer",
              minWidth: 180,
            }}
          >
            {monthOptions.map((m) => (
              <option key={m.key} value={m.key}>
                {m.label}
              </option>
            ))}
          </select>
        </div>

        <input
          ref={inputRef}
          type="file"
          multiple
          accept=".csv,.xlsx,.xls"
          onChange={(e) => addFiles(e.target.files)}
          style={{ marginBottom: 8 }}
        />

        {files.length > 0 && (
          <div
            style={{
              border: "1px solid #eee",
              borderRadius: 10,
              padding: 12,
              marginTop: 10,
              marginBottom: 14,
            }}
          >
            <div style={{ fontWeight: 800, marginBottom: 8 }}>
              Selected files: {files.length}
            </div>

            <ul style={{ margin: 0, paddingLeft: 18 }}>
              {files.map((f, idx) => (
                <li key={`${f.name}|${f.size}|${f.lastModified}`}>
                  {f.name}{" "}
                  <button
                    type="button"
                    onClick={() => removeFile(idx)}
                    disabled={loading}
                    style={{
                      marginLeft: 8,
                      cursor: loading ? "not-allowed" : "pointer",
                      opacity: loading ? 0.6 : 1,
                    }}
                  >
                    remove
                  </button>
                </li>
              ))}
            </ul>

            <div style={{ marginTop: 10 }}>
              <button
                type="button"
                onClick={clearFiles}
                disabled={loading}
                style={{
                  padding: "6px 10px",
                  cursor: loading ? "not-allowed" : "pointer",
                  opacity: loading ? 0.7 : 1,
                }}
              >
                Clear selection
              </button>
            </div>
          </div>
        )}

        <div
          style={{
            fontSize: 12,
            opacity: 0.75,
            marginBottom: 14,
            lineHeight: 1.4,
          }}
        >
          Supported right now: <b>CSV</b> exports (recommended). Basic Excel
          support is evolving.
          <br />
          Expected columns: <b>Date</b>, <b>Description/Merchant</b>,{" "}
          <b>Amount</b> (column names may vary by bank).
          <br />
          You can select multiple files at once, or add more files in multiple
          picks — analysis runs only when you click <b>Upload & Analyze</b>.
        </div>

        <div>
          <button
            onClick={handleUpload}
            disabled={loading || files.length === 0}
            style={{
              padding: "8px 14px",
              cursor: loading ? "not-allowed" : "pointer",
              opacity: loading || files.length === 0 ? 0.7 : 1,
            }}
          >
            {loading ? "Uploading..." : `Upload & Analyze (${formatMonthLabel(selectedMonth)})`}
          </button>
        </div>

        {message && (
          <div
            style={{
              marginTop: 12,
              color:
                message.toLowerCase().includes("complete") ||
                message.toLowerCase().includes("success")
                  ? "green"
                  : "crimson",
            }}
          >
            {message}
          </div>
        )}
      </div>

      {/* Results */}
      {!hasAi ? (
        <div
          style={{
            border: "1px solid #eee",
            borderRadius: 12,
            padding: 18,
            opacity: 0.85,
          }}
        >
          <div style={{ fontWeight: 800, marginBottom: 8 }}>
            Spending Synopsis
          </div>

          <div style={{ lineHeight: 1.5 }}>
            Once uploaded, AI will analyze your transactions and generate a
            structured breakdown of your expenses.
          </div>

          <div style={{ marginTop: 10 }}>
            You’ll receive:
            <ul style={{ marginTop: 8 }}>
              <li>
                Totals by category (Subscriptions, Dining, Groceries, Bills,
                Shopping, Transport, etc.)
              </li>
              <li>Top merchants within each category</li>
              <li>
                AI-generated insights highlighting patterns, recurring charges,
                and savings opportunities
              </li>
            </ul>
          </div>
        </div>
      ) : (
        <div style={{ marginTop: 6 }}>
          {/* Top stats */}
          <div style={{ display: "flex", gap: 12, flexWrap: "wrap" }}>
            <Stat label="Transactions Used" value={result!.transactionCount} />

            <Stat label="Total Expenses" value={Number(result!.ai.totalExpenses)} money />
			{typeof result!.ai.payrollTotal === "number" && (
				<Stat label="Payroll" value={Number(result!.ai.payrollTotal)} money />
			)}
            {typeof result!.ai.billPaymentsTotal === "number" && (
              <Stat label="Bill Payment" value={Number(result!.ai.billPaymentsTotal)} money />
            )}
          </div>

          {/* Categories */}
          <div
            style={{
              marginTop: 16,
              border: "1px solid #eee",
              borderRadius: 12,
              padding: 18,
            }}
          >
            <div style={{ fontWeight: 800, marginBottom: 10 }}>
              Spending by Category
            </div>

            {localCategories.map((c) => (
              <div key={c.category} style={{ marginBottom: 16 }}>
                <div style={{ fontWeight: 900 }}>
                  {c.category} — {fmtMoney(Number(c.total))}
                </div>

                <div style={{ marginTop: 8 }}>
                  <table style={{ width: "100%", borderCollapse: "collapse" }}>
                    <thead>
                      <tr>
                        <th style={th}>Merchant</th>
                        <th style={th}>Amount</th>
                      <th style={th}>Move To</th>
                      </tr>
                    </thead>
                    <tbody>
  {(c.merchants || []).map((m) => (
    <tr key={`${c.category}-${m.merchant}`}>
      <td style={td}>{m.merchant}</td>
      <td style={td}>{fmtMoney(Number(m.amount))}</td>
      <td style={td}>
<select
  value={c.category}
  onChange={(e) => {
    const selected = e.target.value;

    if (selected === "__new__") {
      const name = prompt("Enter new category name:");
      if (!name) return;

      const cleaned = name.trim();
      if (!cleaned) return;

      moveMerchant(
        c.category,
        m.merchant,
        Number(m.amount),
        cleaned
      );
    } else {
      moveMerchant(
        c.category,
        m.merchant,
        Number(m.amount),
        selected
      );
    }
  }}
>
  {localCategories.map((opt) => (
    <option key={opt.category} value={opt.category}>
      {opt.category}
    </option>
  ))}

  <option value="__new__">+ Create New Category</option>
</select>
      </td>
    </tr>
  ))}
</tbody>
                  </table>
                </div>
              </div>
            ))}

            <div style={{ marginTop: 10, fontSize: 12, opacity: 0.7 }}>
              Source file: {result!.filename}
            </div>

            {(hasStructuredInsights || hasLegacyNotes) && (
              <div style={{ marginTop: 16, fontSize: 13, opacity: 0.95 }}>
                <div style={{ fontWeight: 900, marginBottom: 8 }}>AI Insights</div>

                {hasStructuredInsights ? (
                  <div style={{ lineHeight: 1.55 }}>
                    {hasAny(insights?.highlights) && (
                      <ul style={{ marginTop: 0, marginBottom: 10, paddingLeft: 18 }}>
                        {insights!.highlights!.map((h, idx) => (
                          <li key={`hl-${idx}`}>{h}</li>
                        ))}
                      </ul>
                    )}

                    {(insights?.topSpendingCategory || insights?.topMerchant) && (
                      <div style={{ marginBottom: 10 }}>
                        {insights?.topSpendingCategory && (
                          <div>
                            <b>Top category:</b> {insights.topSpendingCategory}
                          </div>
                        )}
                        {insights?.topMerchant && (
                          <div>
                            <b>Top merchant:</b> {insights.topMerchant}
                          </div>
                        )}
                      </div>
                    )}

                    {hasAny(insights?.concentrationNotes) && (
                      <div style={{ marginBottom: 10 }}>
                        <div style={{ fontWeight: 800, marginBottom: 4 }}>
                          Concentration
                        </div>
                        <ul style={{ marginTop: 0, marginBottom: 0, paddingLeft: 18 }}>
                          {insights!.concentrationNotes!.map((n, idx) => (
                            <li key={`cn-${idx}`}>{n}</li>
                          ))}
                        </ul>
                      </div>
                    )}

                    {hasAny(insights?.optimizationIdeas) && (
                      <div style={{ marginBottom: 10 }}>
                        <div style={{ fontWeight: 800, marginBottom: 4 }}>
                          Optimization ideas
                        </div>
                        <ul style={{ marginTop: 0, marginBottom: 0, paddingLeft: 18 }}>
                          {insights!.optimizationIdeas!.map((n, idx) => (
                            <li key={`op-${idx}`}>{n}</li>
                          ))}
                        </ul>
                      </div>
                    )}

                    {hasAny(insights?.anomalies) && (
                      <div style={{ marginBottom: 0 }}>
                        <div style={{ fontWeight: 800, marginBottom: 4 }}>
                          Anomalies
                        </div>
                        <ul style={{ marginTop: 0, marginBottom: 0, paddingLeft: 18 }}>
                          {insights!.anomalies!.map((n, idx) => (
                            <li key={`an-${idx}`}>{n}</li>
                          ))}
                        </ul>
                      </div>
                    )}
                  </div>
                ) : (
                  <div style={{ whiteSpace: "pre-wrap", lineHeight: 1.55 }}>
                    {result!.ai.notes}
                  </div>
                )}
              </div>
            )}
          </div>
        </div>
      )}
    </div>
  );
}

function Stat({
  label,
  value,
  money,
}: {
  label: string;
  value: number;
  money?: boolean;
}) {
  return (
    <div
      style={{
        padding: 14,
        border: "1px solid #e5e7eb",
        borderRadius: 12,
        minWidth: 220,
      }}
    >
      <div style={{ fontSize: 12, opacity: 0.7 }}>{label}</div>
      <div style={{ fontSize: 20, fontWeight: 900 }}>
        {money ? fmtMoney(value) : value}
      </div>
    </div>
  );
}

const th: React.CSSProperties = {
  textAlign: "left",
  borderBottom: "1px solid #eee",
  padding: "10px 8px",
  fontSize: 13,
  opacity: 0.8,
};

const td: React.CSSProperties = {
  borderBottom: "1px solid #f1f1f1",
  padding: "10px 8px",
};

function fmtMoney(n: number) {
  return new Intl.NumberFormat("en-US", {
    style: "currency",
    currency: "USD",
  }).format(n);
}

function hasAny(arr?: unknown[]) {
  return Array.isArray(arr) && arr.length > 0;
}

function buildRecentMonthOptions(count: number): { key: string; label: string }[] {
  const out: { key: string; label: string }[] = [];
  const d = new Date();
  d.setDate(1);

  for (let i = 0; i < count; i++) {
    const y = d.getFullYear();
    const m = String(d.getMonth() + 1).padStart(2, "0");
    const key = `${y}-${m}`;
    out.push({ key, label: formatMonthLabel(key) });
    d.setMonth(d.getMonth() - 1);
  }

  return out;
}

function formatMonthLabel(monthKey: string) {
  // monthKey: "YYYY-MM"
  const [yy, mm] = monthKey.split("-");
  const y = Number(yy);
  const m = Number(mm);
  const dt = new Date(y, m - 1, 1);
  return dt.toLocaleString("en-US", { month: "short", year: "numeric" });
}
