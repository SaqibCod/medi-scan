"use client";

import { Button, Card, Eyebrow, Helix } from "@/components/ds";
import { copy } from "@/lib/copy";
import { formatUploadedAt, sourceTypeLabel } from "@/lib/report-format";
import type { ReportResponse } from "@/lib/api-types";

/**
 * The waiting page.
 *
 * **No fake progress (B3).** The backend reports `PENDING` or `PROCESSING` and nothing
 * finer, so there is no honest way to light step 3 of 5. The steps are here as an
 * explanation of what is happening to the file, not as a tracker, and the only thing that
 * moves is an indeterminate line that claims nothing. A bar creeping to 80% and stopping is
 * a lie the page would be telling on every upload.
 */
const STEPS = [
  { title: "Read", body: copy.steps.read },
  { title: "Mask", body: copy.steps.mask },
  { title: "Extract", body: copy.steps.extract },
  { title: "Flag", body: copy.steps.flag },
  { title: "Explain", body: copy.steps.explain },
];

export function ProcessingView({
  report,
  tookTooLong,
  onKeepWaiting,
  onDelete,
}: {
  report: ReportResponse;
  tookTooLong: boolean;
  onKeepWaiting: () => void;
  onDelete: () => void;
}) {
  const queued = report.status === "PENDING";

  return (
    <div className="grid gap-space-8 lg:grid-cols-[minmax(0,1.3fr)_minmax(0,1fr)] lg:gap-space-9">
      <div>
        <Eyebrow>
          {`Report · ${sourceTypeLabel(report.sourceType)} · uploaded ${formatUploadedAt(report.createdAt)}`}
        </Eyebrow>

        <h1 className="t-display-l mt-space-4 max-w-[16ch] text-ink">
          Reading your report.
          <br />
          <span className="text-faint">This page updates itself.</span>
        </h1>

        <div className="mt-space-7">
          <p className="t-meta flex items-center gap-space-3 text-accent-ink">
            <span className="h-2 w-2 rounded-full bg-accent" aria-hidden="true" />
            {queued ? "QUEUED" : "PROCESSING"}
          </p>

          {/*
            Indeterminate on purpose: it says "something is happening", which is all we know
            (B3). This reuses the waking banner's line rather than adding a second one —
            same treatment, and ds.css already stills it under reduced motion, where a
            animation nobody asked for is worse than no animation.
          */}
          <div className="relative mt-space-4 h-0.5 w-full bg-line" aria-hidden="true">
            <span className="ms-banner__progress" />
          </div>

          {/*
            Announces only the four state changes, not every poll. A live region tied to the
            query would say "processing" every 1.5 seconds.
          */}
          <p className="sr-only" role="status">
            {queued ? "Your report is queued." : "Your report is being read."}
          </p>
        </div>

        <ol className="mt-space-8">
          {STEPS.map((step, index) => (
            <li key={step.title} className="flex gap-space-5 border-t border-line py-space-4">
              <span className="t-meta shrink-0 text-ink-3">{`0${index + 1}`}</span>
              <span>
                <span className="t-body block text-ink">{step.title}</span>
                <span className="t-body-s text-ink-2">{step.body}</span>
              </span>
            </li>
          ))}
        </ol>

        {tookTooLong ? (
          <div className="mt-space-7 rounded-md bg-surface-2 px-space-5 py-space-5">
            <p className="t-body text-ink">This is taking longer than usual.</p>
            <p className="t-body-s mt-space-2 text-ink-2">
              The server may be busy or waking up. Your report is still queued — nothing has
              been lost.
            </p>
            <div className="mt-space-5 flex flex-wrap gap-space-3">
              <Button onClick={onKeepWaiting}>Keep waiting</Button>
              <Button variant="outline" icon="trash" onClick={onDelete}>
                Cancel and delete
              </Button>
            </div>
          </div>
        ) : (
          <div className="mt-space-7 flex flex-col gap-space-4 md:flex-row md:items-center">
            <Button variant="outline" icon="trash" onClick={onDelete}>
              Cancel and delete
            </Button>
            <p className="t-body-s max-w-[36ch] text-ink-2">
              Keep this tab open. If it takes longer than 2 minutes, we&apos;ll tell you here.
            </p>
          </div>
        )}
      </div>

      <div className="hidden lg:block">
        <Card className="flex h-[300px] items-center justify-center overflow-hidden">
          <Helix width={300} height={540} />
        </Card>
        <ResultsSkeleton />
      </div>
    </div>
  );
}

/** The shape the results will take, so the page does not jump when they land. */
function ResultsSkeleton() {
  return (
    <Card className="mt-space-4" aria-hidden="true">
      <div className="flex flex-col gap-space-3">
        <div className="h-3 w-1/3 rounded-sm bg-surface-2" />
        <div className="h-3 w-full rounded-sm bg-surface-2" />
        <div className="h-3 w-2/3 rounded-sm bg-surface-2" />
      </div>
      <div className="mt-space-6 flex flex-col gap-space-4">
        {[0, 1, 2, 3].map((row) => (
          <div key={row} className="flex items-center gap-space-4">
            <div className="h-3 flex-1 rounded-sm bg-surface-2" />
            <div className="h-3 w-16 rounded-sm bg-surface-2" />
            <div className="h-5 w-16 rounded-full bg-surface-2" />
          </div>
        ))}
      </div>
    </Card>
  );
}
