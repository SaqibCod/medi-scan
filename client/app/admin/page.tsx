import type { Metadata } from "next";

import { PhasePlaceholder } from "@/components/layout/phase-placeholder";

export const metadata: Metadata = {
  title: "Admin",
  description: "Aggregated usage statistics.",
  robots: { index: false, follow: false },
};

export default function AdminPage() {
  return (
    <PhasePlaceholder heading="Admin" phase="phase 6">
      <p>
        A usage dashboard: AI calls and tokens per day against the daily cap, report counts by
        status and failure code, average processing time per source type, masking-conflict
        counts, and rate-limit rejections.
      </p>
      <p>
        It will only ever show aggregated counts — never report content, never another
        user&rsquo;s report ids, never user names. The numbers come from daily counter tables
        that are deliberately separate from reports, so they survive retention.
      </p>
      <p>
        The admin role is granted from configuration alone. No endpoint and no screen in this
        app can hand it out.
      </p>
    </PhasePlaceholder>
  );
}
