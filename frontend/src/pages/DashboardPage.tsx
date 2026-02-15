import { useNavigate } from "react-router-dom";

export default function DashboardPage() {
  const navigate = useNavigate();

  const parentCardStyle: React.CSSProperties = {
    border: "1px solid #e5e7eb",
    borderRadius: 14,
    padding: 16,
    boxShadow: "0 1px 2px rgba(0,0,0,0.05)",
  };

  const childCardStyle: React.CSSProperties = {
    border: "1px solid #e5e5e5",
    borderRadius: 10,
    padding: 14,
    cursor: "pointer",
    transition: "all 0.15s ease",
  };

  const comingSoonStyle: React.CSSProperties = {
    marginTop: 12,
    fontSize: 13,
    opacity: 0.65,
  };

  const dashedCardStyle: React.CSSProperties = {
    border: "1px dashed #d1d5db",
    borderRadius: 14,
    padding: 16,
    opacity: 0.7,
  };

  return (
    <div>
      <h2 style={{ marginBottom: 6 }}>Dashboard</h2>
      <div style={{ opacity: 0.75, marginBottom: 18 }}>Choose a module</div>

      <div
        style={{
          display: "grid",
          gridTemplateColumns: "repeat(auto-fit, minmax(260px, 1fr))",
          gap: 14,
        }}
      >
        {/* Product Suite (parent card) */}
        <div style={parentCardStyle}>
          <div style={{ fontSize: 18, fontWeight: 800, marginBottom: 10 }}>
            Product Suite
          </div>

          {/* AI Reviewer (child card) */}
          <div
            onClick={() => navigate("/ai-reviewer")}
            style={childCardStyle}
            title="Open AI User Story Reviewer"
          >
            <div style={{ fontWeight: 700, fontSize: 15, marginBottom: 6 }}>
              AI User Story Reviewer
            </div>

            <div style={{ opacity: 0.8, lineHeight: 1.35 }}>
              Score user stories, detect gaps &amp; edge cases, generate Gherkin
              ACs, and produce Jira-ready review comments.
            </div>
          </div>

          <div style={comingSoonStyle}>
            Coming soon: AI User Story Generator · AI PRD Builder · AI Backlog
            Optimizer
          </div>
        </div>

        {/* Finance (parent card) */}
        <div style={parentCardStyle}>
          <div style={{ fontSize: 18, fontWeight: 800, marginBottom: 10 }}>
            Finance
          </div>

          {/* Spending Intelligence (child card) */}
          <div
            onClick={() => navigate("/spending-intelligence")}
            style={childCardStyle}
            title="Open AI Spending Intelligence"
          >
            <div style={{ fontWeight: 700, fontSize: 15, marginBottom: 6 }}>
              AI Spending Intelligence
            </div>

            <div style={{ opacity: 0.8, lineHeight: 1.35 }}>
              Upload your bank export and get monthly summaries, category
              breakdowns, and AI-powered insights.
            </div>
          </div>

          <div style={comingSoonStyle}>
            Coming soon: Portfolio Analyzer · Fund Comparison · Reverse CAGR
          </div>
        </div>
        
      </div>
    </div>
  );
}
