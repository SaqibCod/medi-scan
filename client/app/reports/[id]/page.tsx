import type { Metadata } from "next";

import { ReportView } from "@/components/report/report-view";
import { PageContainer } from "@/components/shell/page-container";

/**
 * The report page.
 *
 * The title is the same in every state and names nothing about the report. A title reaches
 * browser history, the window switcher and any screenshot of the tab bar, so a test name in
 * it would outlive the 24 hours the report itself gets.
 *
 * `noindex` because the URL is only meaningful to its owner, and a crawler following one
 * would get a 404 anyway.
 */
export const metadata: Metadata = {
  title: "Your report",
  robots: { index: false, follow: false },
};

export default async function ReportPage({ params }: { params: Promise<{ id: string }> }) {
  const { id } = await params;

  return (
    <PageContainer>
      <ReportView id={id} />
    </PageContainer>
  );
}
