import type { Metadata } from "next";

import { PhasePlaceholder } from "@/components/layout/phase-placeholder";

export const metadata: Metadata = {
  title: "Your Report",
  description: "Your lab results, explained in plain language.",
  // A report URL is a capability: anyone holding the id can open it. Keep it out of indexes
  // and out of the referrer.
  robots: { index: false, follow: false },
};

export default async function ReportPage({ params }: PageProps<"/report/[id]">) {
  const { id } = await params;

  return (
    <PhasePlaceholder heading="Your Report" phase="phase 2">
      <p>
        This page will show the report&rsquo;s processing status while it runs, then every result
        with its value, unit, reference range and whether it is low, normal or high — followed by
        a plain-language summary and a chat box for follow-up questions.
      </p>
      <p>
        Nothing is fetched yet. The backend has the session and ownership checks in place, so
        this page will only ever be able to load a report belonging to this browser tab.
      </p>
      <p className="text-sm">
        Report&nbsp;id:{" "}
        <code className="rounded bg-muted px-1.5 py-0.5 font-mono text-xs break-all" translate="no">
          {id}
        </code>
      </p>
    </PhasePlaceholder>
  );
}
