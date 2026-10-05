import type { Metadata } from "next";

import { PhasePlaceholder } from "@/components/layout/phase-placeholder";

export const metadata: Metadata = {
  title: "Biomarker Reference",
  description:
    "Plain-language reference pages explaining what common lab biomarkers measure and how to read them against your report's reference range.",
};

export default function BiomarkersIndexPage() {
  return (
    <PhasePlaceholder heading="Biomarker Reference" phase="phase 7">
      <p>
        An index of curated pages, one per biomarker, each explaining what the test measures,
        why it gets ordered, how to read it against the range printed on your own report, and
        what can move a value without it meaning anything is wrong.
      </p>
      <p>
        These pages do double duty: they are the statically generated reference section, and
        they are the knowledge base the chat answers draw on. Chat retrieval splits them by
        heading and cites the heading it used, which is why every page shares the same five
        headings.
      </p>
      <p>
        The content is written and reviewed by hand, with sources, rather than generated. One
        structural example lives in{" "}
        <code className="rounded bg-muted px-1.5 py-0.5 font-mono text-xs" translate="no">
          content/biomarkers/
        </code>{" "}
        and is marked as a draft.
      </p>
    </PhasePlaceholder>
  );
}
