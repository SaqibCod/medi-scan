"use client";

import { Button, Card, Icon } from "@/components/ds";
import { failureCopy } from "@/lib/copy";
import type { ReportResponse } from "@/lib/api-types";

/**
 * A report that could not be read.
 *
 * Both actions go somewhere that works: back to the upload panel, or to the samples. A
 * failed report with only a "Delete" button would be a dead end, and the most likely thing
 * the person wants is to try the other way in.
 *
 * The code line names the report rather than a request (B9). A job that failed in the
 * background has no request id to quote, and the first eight characters of the report id are
 * what makes a log searchable.
 */
export function FailedView({
  report,
  onDelete,
}: {
  report: ReportResponse;
  onDelete: () => void;
}) {
  const copy = failureCopy(report.error?.code);

  return (
    <div className="flex justify-center py-space-8">
      <Card variant="panel" className="w-full max-w-[760px]">
        <span className="flex h-12 w-12 items-center justify-center rounded-sm bg-flag-high-bg text-flag-high-fg">
          <Icon name="file" size={22} />
        </span>

        {/* Orange, never red: this is a document we could not read, not an emergency. */}
        <p className="t-eyebrow mt-space-6 text-flag-high-fg">{copy.eyebrow}</p>

        <h1 className="t-display-m mt-space-4 max-w-[20ch] text-ink">{copy.headline}</h1>

        <p className="t-body mt-space-5 max-w-[52ch] text-ink-2">{copy.body}</p>

        <div className="mt-space-7 flex flex-wrap gap-space-3">
          <Button href="/#upload" icon="upload">
            Try another file
          </Button>
          <Button href="/#samples" variant="outline">
            Try a sample report
          </Button>
        </div>

        <div className="mt-space-7 flex flex-wrap items-center justify-between gap-space-4 border-t border-line pt-space-5">
          <p className="t-meta text-ink-3">
            {report.error?.code ?? "UNKNOWN"} · report {report.id.slice(0, 8)}
          </p>
          <button
            type="button"
            onClick={onDelete}
            className="t-body-s text-accent-ink underline underline-offset-4 focus-visible:outline-2 focus-visible:outline-offset-[3px] focus-visible:outline-accent"
          >
            Delete this report
          </button>
        </div>
      </Card>
    </div>
  );
}
