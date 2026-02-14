import { useNavigate } from "react-router-dom";

export default function DashboardPage() {
  const navigate = useNavigate();

  return (
    <div>
      <h1 style={{ marginBottom: 6 }}>Dashboard</h1>
      <div style={{ opacity: 0.75, marginBottom: 18 }}>Choose a module</div>

      <div style={{ display: "grid", gridTemplateColumns: "repeat(auto-fit, minmax(260px, 1fr))", gap: 14 }}>
        <div
          onClick={() => navigate("/pm")}
          style={{
            border: "1px solid #e5e7eb",
            borderRadius: 14,
            padding: 16,
            cursor: "pointer",
            boxShadow: "0 1px 2px rgba(0,0,0,0.05)",
          }}
        >
          <div style={{ fontSize: 18, fontWeight: 800, marginBottom: 6 }}>Product Manager / Product Owner</div>
          <div style={{ opacity: 0.8, lineHeight: 1.35 }}>
            AI User Story Reviewer (score, gaps, edge cases, Gherkin ACs, Jira comments)
          </div>
          <div style={{ marginTop: 10, fontSize: 13, opacity: 0.7 }}>
            Coming soon: Prompt → full user story generator
          </div>
        </div>

        <div style={{ border: "1px dashed #d1d5db", borderRadius: 14, padding: 16, opacity: 0.7 }}>
          <div style={{ fontSize: 18, fontWeight: 800, marginBottom: 6 }}>Investing</div>
          <div>Portfolio calculator, fund comparisons, reverse calculator (future)</div>
        </div>

        <div style={{ border: "1px dashed #d1d5db", borderRadius: 14, padding: 16, opacity: 0.7 }}>
          <div style={{ fontSize: 18, fontWeight: 800, marginBottom: 6 }}>Trading</div>
          <div>Journal, checklist, review tools (future)</div>
        </div>
      </div>
    </div>
  );
}
