import type { Metadata } from "next";

import { PhasePlaceholder } from "@/components/layout/phase-placeholder";

export const metadata: Metadata = {
  title: "My Reports",
  description: "Your report history, kept for 30 days when you are signed in.",
  robots: { index: false, follow: false },
};

export default function HistoryPage() {
  return (
    <PhasePlaceholder heading="My Reports" phase="phase 6">
      <p>
        Once Google sign-in exists, this page will list your reports newest first, with the
        number of in- and out-of-range results for each, and link through to biomarker trend
        charts across reports.
      </p>
      <p>
        History is one of only three things that need an account — the others are trends and the
        admin dashboard. Uploading a report, reading the results and asking questions all work
        as a guest, and will keep working that way.
      </p>
      <p>Signed-in history is kept for 30 days. Guest reports are deleted after 24 hours.</p>
    </PhasePlaceholder>
  );
}
