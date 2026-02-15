import React, { useRef, useState } from "react";
import { useAuth } from "@clerk/clerk-react";

type AiMerchant = {
  merchant: string;
  amount: number;
};

type AiCategory = {
  category: string;
  total: number;
  merchants: AiMerchant[];
};

type AiResult = {
  totalExpenses: number;
  billPaymentsTotal?: number;
  categories: AiCategory[];
  notes?: string;
};

type UploadAiResponse = {
  ok: boolean;
  filename: string;
  transactionCount: number;
  ai: AiResult;
};

export default function SpendingIntelligencePage() {
  const { getToken } = useAuth();

  // CHANGED: support multiple files + incremental selection
  const [files, setFiles] = useState<File[]>([]);
  const inputRef = useRef<HTMLInputElement | null>(null);

  const [loading, setLoading] = useState(false);
  const [message, setMessage] = useState<string | null>(null);
  const [result, setResult] = useState<UploadAiResponse | null>(null);

  function addFiles(fileList: FileList | null) {
    if (!fileList || fileList.length === 0) return;

    const incoming = Array.from(fileList);

    setFiles((prev) => {
      // Client-side dedup so user can select same file in multiple picks without duplicates.
      const seen = new Set(prev.map((f) => `${f.name}|${f.size}|${f.lastModified}`));
      const merged = [...prev];

      for (const f of incoming) {
        const key = `${f.name}|${f.size}|${f.lastModified}`;
        if (!seen.has(key)) merged.push(f);
      }
      return merged;
    });

    // Reset input so selecting the same file again triggers onChange.
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

      // CHANGED: send all selected files; backend should accept @RequestParam("files") List<MultipartFile>
      files.forEach((f) => formData.append("files", f));

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

      // Optional: clear selected files after success
      clearFiles();

      setMessage("File uploaded successfully. AI analysis complete.");
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
            {loading ? "Uploading..." : "Upload & Analyze"}
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
                AI-generated notes highlighting patterns, recurring charges, and
                savings opportunities
              </li>
            </ul>
          </div>
        </div>
      ) : (
        <div style={{ marginTop: 6 }}>
          {/* Top stats */}
          <div style={{ display: "flex", gap: 12, flexWrap: "wrap" }}>
            <Stat label="Transactions Used" value={result!.transactionCount} />

            <Stat
              label="Total Expenses"
              value={Number(result!.ai.totalExpenses)}
              money
            />

            {typeof result!.ai.billPaymentsTotal === "number" && (
              <Stat
                label="Bill Payment"
                value={Number(result!.ai.billPaymentsTotal)}
                money
              />
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

            {result!.ai.categories.map((c) => (
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
                      </tr>
                    </thead>
                    <tbody>
                      {(c.merchants || []).map((m) => (
                        <tr key={`${c.category}-${m.merchant}`}>
                          <td style={td}>{m.merchant}</td>
                          <td style={td}>{fmtMoney(Number(m.amount))}</td>
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

            {result!.ai.notes && (
              <div style={{ marginTop: 14, fontSize: 13, opacity: 0.9 }}>
                <div style={{ fontWeight: 800, marginBottom: 4 }}>
                  AI Insights
                </div>
                <div style={{ whiteSpace: "pre-wrap" }}>{result!.ai.notes}</div>
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
