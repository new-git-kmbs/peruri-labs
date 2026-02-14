import { SignedIn, SignedOut, SignInButton, UserButton } from "@clerk/clerk-react";
import { BrowserRouter, Routes, Route, Navigate, Link } from "react-router-dom";
import DashboardPage from "./pages/DashboardPage";
import AiReviewerPage from "./pages/AiReviewerPage";

export default function App() {
  return (
    <BrowserRouter>
      <div style={{ maxWidth: 1100, margin: "24px auto", padding: 12, fontFamily: "system-ui" }}>
        {/* Top bar */}
        <div style={{ display: "flex", alignItems: "center", justifyContent: "space-between", marginBottom: 14 }}>
          <Link to="/dashboard" style={{ textDecoration: "none", color: "inherit" }}>
            <div style={{ fontWeight: 800 }}>My Platform</div>
          </Link>

          <div>
            <SignedOut>
              <SignInButton mode="modal">
                <button style={{ padding: "8px 12px" }}>Sign In</button>
              </SignInButton>
            </SignedOut>
            <SignedIn>
              <UserButton afterSignOutUrl="/dashboard" />
            </SignedIn>
          </div>
        </div>

        {/* Routes */}
        <SignedOut>
          <Routes>
            <Route path="*" element={<Navigate to="/dashboard" replace />} />
            <Route path="/dashboard" element={<DashboardPage />} />
          </Routes>
        </SignedOut>

        <SignedIn>
          <Routes>
            <Route path="/" element={<Navigate to="/dashboard" replace />} />
            <Route path="/dashboard" element={<DashboardPage />} />
            <Route path="/pm" element={<AiReviewerPage />} />
            <Route path="*" element={<Navigate to="/dashboard" replace />} />
          </Routes>
        </SignedIn>
      </div>
    </BrowserRouter>
  );
}
