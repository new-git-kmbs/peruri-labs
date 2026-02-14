import { useState } from "react";
import { SignedIn, SignedOut, SignInButton, UserButton, useAuth } from "@clerk/clerk-react";

type ReviewResponse = {
  rating: {
    score_1_to_5: number;
    label: string;
    one_line_summary: string;
    critical: number;
    major: number;
    minor: number;
  };
  data: {
    missing_acceptance_criteria?: any[];
    ambiguous_language?: any[];
    edge_cases?: any[];
    non_testable_or_weak_criteria?: any[];
    missing_context_questions?: any[];
  };
  rewrite?: {
    user_story?: string;
    acceptance_criteria?: string[];
  };
  jira_comment_md?: string;
  error?: {
    message?: string;
    hint?: string;
  };
};

export default function AiReviewerPage() {
  const { getToken, isSignedIn } = useAuth();

  const [context, setContext] = useState("");
  const [story, setStory] = useState("");
  const [acceptanceCriteria, setAcceptanceCriteria] = useState("");
  const [loading, setLoading] = useState(false);
  const [res, setRes] = useState<ReviewResponse | null>(null);
  const [err, setErr] = useState<string | null>(null);

  async function onReview() {
    const API_BASE = import.meta.env.VITE_API_BASE_URL as string | undefined;
    console.log("VITE_API_BASE_URL =", API_BASE);

    setErr(null);
    setRes(null);
    setLoading(true);

    try {
      if (!API_BASE || API_BASE.trim().length === 0) {
        throw new Error(
          "Missing VITE_API_BASE_URL. Set it in Vercel (Production) and redeploy. For local dev, add frontend/.env.local."
        );
      }

      if (!isSignedIn) {
        throw new Error("Please sign in first.");
      }

      const token = await getToken();
      if (!token) {
        throw new Error("Could not get auth token. Try signing out and signing in again.");
      }

      const r = await fetch(`${API_BASE}/api/review`, {
        method: "POST",
        headers: {
          "Content-Type": "application/json",
          Authorization: `Bearer ${token}`,
        },
        body: JSON.stringify({ context, story, acceptanceCriteria }),
      });

      const contentType = r.headers.get("content-type") || "";
      const raw = await r.text();

      let json: ReviewResponse | null = null;
      if (contentType.includes("application/json")) {
        try {
          json = JSON.parse(raw) as ReviewResponse;
        } catch {
          // handled below
        }
      }

      if (!r.ok) {
        const msg =
          json?.error?.message
            ? `Backend error: ${r.status} — ${json.error.message}`
            : `Backend error: ${r.status} — ${raw.slice(0, 200)}`;
        throw new Error(msg);
      }

      if (!json) {
        throw new Error(
          `Expected JSON but got: ${contentType || "(no content-type)"} — ${raw.slice(0, 200)}`
        );
      }

      setRes(json);
    } catch (e: any) {
      setErr(e?.message ?? "Unknown error");
    } finally {
      setLoading(false);
    }
  }

  return (
    <div style={{ maxWidth: 980, margin: "24px auto", fontFamily: "system-ui", padding: 12 }}>
      <h1 style={{ marginBottom: 4 }}>AI User Story Reviewer</h1>

      <div style={{ display: "flex", justifyContent: "flex-end", marginBottom: 12 }}>
        <SignedOut>
          <SignInButton mode="modal">
            <button style={{ padding: "8px 12px" }}>Sign In</button>
          </SignInButton>
        </SignedOut>

        <SignedIn>
          <UserButton afterSignOutUrl="/" />
        </SignedIn>
      </div>

      <div style={{ opacity: 0.8, marginBottom: 16 }}>
        Paste a story and click Review to get a score + gaps + questions.
      </div>

      <label style={{ fontWeight: 600 }}>Context (optional)</label>
      <textarea
        value={context}
        onChange={(e) => setContext(e.target.value)}
        rows={3}
        style={{ width: "100%", marginTop: 6, marginBottom: 12 }}
        placeholder="Example: Mobile app, logged-in users, subscriptions exist..."
      />

      <label style={{ fontWeight: 600 }}>User Story / Requirement *</label>
      <textarea
        value={story}
        onChange={(e) => setStory(e.target.value)}
        rows={6}
        style={{ width: "100%", marginTop: 6, marginBottom: 12 }}
        placeholder="As a..., I want..., so that..."
      />

      <label style={{ fontWeight: 600 }}>Acceptance Criteria (optional)</label>
      <textarea
        value={acceptanceCriteria}
        onChange={(e) => setAcceptanceCriteria(e.target.value)}
        rows={5}
        style={{ width: "100%", marginTop: 6, marginBottom: 12 }}
        placeholder="Paste AC here (or leave blank)"
      />

      <button
        onClick={onReview}
        disabled={loading || story.trim().length === 0}
        style={{ padding: "10px 14px", cursor: loading ? "not-allowed" : "pointer" }}
      >
        {loading ? "Reviewing..." : "Review"}
      </button>

      {err && <div style={{ marginTop: 12, color: "crimson" }}>{err}</div>}

      {res?.error?.message && (
        <div style={{ marginTop: 12, border: "1px solid #f2c1c1", borderRadius: 10, padding: 12 }}>
          <div style={{ color: "crimson", fontWeight: 800 }}>AI Error</div>
          <div style={{ marginTop: 6 }}>{res.error.message}</div>
          {res.error.hint && <div style={{ marginTop: 6, opacity: 0.85 }}>{res.error.hint}</div>}
        </div>
      )}

      {res && (
        <div style={{ marginTop: 18 }}>
          <div style={{ border: "1px solid #ddd", borderRadius: 10, padding: 12 }}>
            <div style={{ fontSize: 18, fontWeight: 800 }}>
              {res.rating.score_1_to_5}/5 — {res.rating.label}
            </div>
            <div style={{ marginTop: 6 }}>{res.rating.one_line_summary}</div>
            <div style={{ marginTop: 8, fontSize: 13, opacity: 0.8 }}>
              Critical: {res.rating.critical} · Major: {res.rating.major} · Minor: {res.rating.minor}
            </div>
          </div>

          {res.rewrite && (
            <div style={{ marginTop: 14, border: "1px solid #eee", borderRadius: 10, padding: 12 }}>
              <div style={{ fontWeight: 800, marginBottom: 8 }}>Suggested Rewrite</div>

              {res.rewrite.user_story && res.rewrite.user_story.trim().length > 0 && (
                <>
                  <div style={{ fontWeight: 650 }}>Improved User Story</div>
                  <div style={{ whiteSpace: "pre-wrap", marginTop: 4 }}>{res.rewrite.user_story}</div>
                </>
              )}

              {Array.isArray(res.rewrite.acceptance_criteria) &&
                res.rewrite.acceptance_criteria.length > 0 && (
                  <>
                    <div style={{ fontWeight: 650, marginTop: 12 }}>Improved Acceptance Criteria</div>
                    <ul style={{ margin: 0, paddingLeft: 18, marginTop: 6 }}>
                      {res.rewrite.acceptance_criteria.map((line, idx) => (
                        <li key={idx} style={{ marginBottom: 6, whiteSpace: "pre-wrap" }}>
                          {line}
                        </li>
                      ))}
                    </ul>
                  </>
                )}
            </div>
          )}

                   {res.jira_comment_md && res.jira_comment_md.trim().length > 0 && (
            <div style={{ marginTop: 14, border: "1px solid #eee", borderRadius: 10, padding: 12 }}>
              <div style={{ fontWeight: 800, marginBottom: 8 }}>Jira-ready Comment</div>
              <pre style={{ margin: 0, whiteSpace: "pre-wrap" }}>{res.jira_comment_md}</pre>
            </div>
          )}

          <Section title="Missing Acceptance Criteria" items={res.data.missing_acceptance_criteria} />
          <Section title="Ambiguous Language" items={res.data.ambiguous_language} />
          <Section title="Edge Cases" items={res.data.edge_cases} />
          <Section title="Non-testable / Weak Criteria" items={res.data.non_testable_or_weak_criteria} />
          <Section title="Missing Context Questions" items={res.data.missing_context_questions} />
        </div>
      )}
    </div>
  );
}

function Section({ title, items }: { title: string; items?: any[] }) {
  const list = Array.isArray(items) ? items : [];
  return (
    <div style={{ marginTop: 14, border: "1px solid #eee", borderRadius: 10, padding: 12 }}>
      <div style={{ fontWeight: 800, marginBottom: 8 }}>{title}</div>
      {list.length === 0 ? (
        <div style={{ opacity: 0.7 }}>None identified</div>
      ) : (
        <ul style={{ margin: 0, paddingLeft: 18 }}>
          {list.map((it, idx) => (
            <li key={idx} style={{ marginBottom: 10 }}>
              <div style={{ fontWeight: 650 }}>
                {it.title || it.quote || it.question || "Item"}{" "}
                {it.severity ? (
                  <span style={{ fontSize: 12, opacity: 0.7 }}>({it.severity})</span>
                ) : null}
              </div>
              <div style={{ opacity: 0.9 }}>
                {it.details || it.why_ambiguous || it.scenario || it.issue || it.why_needed || ""}
              </div>
              {it.suggested_rewrite && <div style={{ marginTop: 4 }}>Rewrite: {it.suggested_rewrite}</div>}
              {it.testable_rewrite && <div style={{ marginTop: 4 }}>Testable rewrite: {it.testable_rewrite}</div>}
              {it.expected_behavior_question && (
                <div style={{ marginTop: 4 }}>Question: {it.expected_behavior_question}</div>
              )}
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}


