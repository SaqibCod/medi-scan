import { Suspense } from "react";
import type { Metadata } from "next";

import { ReportsView } from "@/components/reports/reports-view";
import { PageContainer } from "@/components/shell/page-container";

/**
 * My reports.
 *
 * `noindex` for the same reason as a report page: the list is only meaningful to whoever owns
 * it, and a crawler following the link would be shown an empty one. The title names no report.
 *
 * The Suspense boundary is required, not decorative: `ReportsView` reads the tab and page from
 * `useSearchParams`, and a client component that does so has to sit inside one or the
 * production build fails.
 */
export const metadata: Metadata = {
  title: "Your reports",
  robots: { index: false, follow: false },
};

export default function ReportsPage() {
  return (
    <PageContainer>
      <Suspense fallback={null}>
        <ReportsView />
      </Suspense>
    </PageContainer>
  );
}
