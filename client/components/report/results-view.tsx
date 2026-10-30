"use client";

import { useMemo, useState } from "react";

import { Disclaimer } from "@/components/shell/disclaimer";
import { RESULTS_DISCLAIMER_EXTRA } from "@/lib/copy";
import { CountTiles, filterLabel, type CountFilter } from "./count-tiles";
import { GuestExpiryNote } from "./guest-expiry-note";
import { ReportHeader } from "./report-header";
import { ResultsTable } from "./results-table";
import { SummaryCard } from "./summary-card";
import type { ReportResponse } from "@/lib/api-types";

/**
 * The done view.
 *
 * One centred column until chat ships (B7). The mockup's two-column layout exists to make
 * room for "Ask about this report", and a 880px column with an empty half beside it would
 * look like something failed to load.
 */
export function ResultsView({
  report,
  onDelete,
}: {
  report: ReportResponse;
  onDelete: () => void;
}) {
  const [filter, setFilter] = useState<CountFilter>(null);
  const result = report.result;

  const shown = useMemo(
    () =>
      filter === null
        ? (result?.biomarkers ?? [])
        : (result?.biomarkers ?? []).filter((b) => b.flag === filter),
    [result, filter],
  );

  if (!result) return null;

  return (
    <div className="mx-auto flex max-w-[880px] flex-col gap-space-7 py-space-8">
      <ReportHeader report={report} onDelete={onDelete} />

      <GuestExpiryNote expiresAt={report.expiresAt} />

      <SummaryCard result={result} />

      <CountTiles counts={result.counts} value={filter} onChange={setFilter} />

      <ResultsTable
        biomarkers={shown}
        reportId={report.id}
        caption={`${filterLabel(filter)} · ${shown.length} of ${result.counts.total}`}
      />

      <Disclaimer extra={RESULTS_DISCLAIMER_EXTRA} />
    </div>
  );
}
