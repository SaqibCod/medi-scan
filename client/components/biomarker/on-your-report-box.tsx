"use client";

import { useSearchParams } from "next/navigation";

import { Button, Card, Eyebrow, FlagChip } from "@/components/ds";
import { formatCollectedOn, formatValue } from "@/lib/report-format";
import { isReportId, useReport } from "@/lib/reports";

/**
 * "On your report · 28 Sep 2026", with the reader's own value.
 *
 * Shown only when the URL carries both ids and that report loads in this tab. It usually
 * costs nothing: arriving from the results table means the report is already in the query
 * cache under the same key.
 *
 * If anything is missing — no params, a report that will not load, a marker that is not in
 * it — the box simply does not appear. No error, no empty frame, no "couldn't load your
 * result". Someone who arrived here from a search engine should see a clean explainer, and
 * someone whose session expired should not be told about it on a page that reads perfectly
 * well without their data.
 *
 * Only ids are ever in the URL. The value is fetched, never passed.
 */
export function OnYourReportBox({ slug }: { slug: string }) {
  const params = useSearchParams();
  const reportId = params.get("report") ?? "";
  const biomarkerId = params.get("b") ?? "";

  const wanted = isReportId(reportId) && biomarkerId !== "";
  const { report } = useReport(wanted ? reportId : "");

  if (!wanted || report?.status !== "DONE" || !report.result) return null;

  const biomarker = report.result.biomarkers.find((candidate) => candidate.id === biomarkerId);
  // Guard the slug too: an id from one report pasted onto another marker's page would
  // otherwise show a value under the wrong heading.
  if (!biomarker || biomarker.biomarkerSlug !== slug) return null;

  const { value, unit } = formatValue(biomarker);
  const collected = report.result.collectedOn;

  return (
    <Card>
      <Eyebrow>
        {collected ? `On your report · ${formatCollectedOn(collected)}` : "On your report"}
      </Eyebrow>

      <p className="mt-space-4 flex flex-wrap items-baseline gap-space-3">
        <span className="t-numeral-xl leading-none text-ink">{value}</span>
        {unit && <span className="t-body text-ink-3">{unit}</span>}
        <FlagChip flag={biomarker.flag} />
      </p>

      <p className="t-body-s mt-space-4 text-ink-2">
        {biomarker.referenceRangeText
          ? `Your lab's range is ${biomarker.referenceRangeText}. Ranges differ between labs, so always use the one on your report.`
          : "Your report did not print a reference range for this test. Ranges differ between labs, so always use the one on your report."}
      </p>

      <div className="mt-space-5 flex flex-wrap gap-space-3">
        <Button href={`/reports/${reportId}`} variant="outline" icon="chevron-left" size="sm">
          Back to report
        </Button>
        {/* "See trend" needs history, which arrives in 7c. */}
      </div>
    </Card>
  );
}
