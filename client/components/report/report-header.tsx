"use client";

import { Button, Eyebrow, Tag } from "@/components/ds";
import {
  formatCollectedOn,
  formatDeletedIn,
  formatUploadedAt,
  sourceTypeLabel,
} from "@/lib/report-format";
import type { ReportResponse } from "@/lib/api-types";

/**
 * The head of the done view: what this report is, and when it goes away.
 *
 * The tags are deliberately plain facts about the document rather than about the person. A
 * missing collection date says so instead of being hidden, because "we couldn't find one" is
 * information about the report and a blank space is not.
 */
export function ReportHeader({
  report,
  onDelete,
}: {
  report: ReportResponse;
  onDelete: () => void;
}) {
  return (
    <header>
      <Eyebrow>Your results</Eyebrow>

      <div className="mt-space-4 flex flex-col gap-space-5 md:flex-row md:items-start md:justify-between">
        <h1 className="t-display-l max-w-[12ch] text-ink">
          Your report,
          <br />
          <span className="text-faint">explained.</span>
        </h1>

        <Button variant="outline" icon="trash" onClick={onDelete}>
          Delete report
        </Button>
      </div>

      <div className="mt-space-5 flex flex-wrap items-center gap-space-3">
        <Tag>{sourceTypeLabel(report.sourceType)}</Tag>
        <span className="t-meta text-ink-3">
          {report.result?.collectedOn
            ? `Collected ${formatCollectedOn(report.result.collectedOn)}`
            : "Collection date not found"}
        </span>
        <span className="t-meta text-ink-3">Uploaded {formatUploadedAt(report.createdAt)}</span>
        <span className="t-meta text-ink-3">Deleted in {formatDeletedIn(report.expiresAt)}</span>
      </div>
    </header>
  );
}
