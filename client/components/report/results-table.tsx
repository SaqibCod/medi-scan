import { BiomarkerRow, Card } from "@/components/ds";
import { RANGE_NOTE } from "@/lib/copy";
import { formatRange, formatValue } from "@/lib/report-format";
import type { Biomarker } from "@/lib/api-types";

/**
 * Every value the report gave, in the order the API returned it.
 *
 * Order matters: it is the order they appear on the document, so someone comparing the
 * screen with the paper in front of them can follow both at once. Sorting by flag would be
 * tidier and much harder to check.
 */
export function ResultsTable({
  biomarkers,
  reportId,
  caption,
}: {
  biomarkers: Biomarker[];
  reportId: string;
  caption: string;
}) {
  return (
    <Card>
      <div className="flex items-baseline justify-between gap-space-4">
        <h2 className="t-title text-ink">{caption.split(" · ")[0]}</h2>
        <p className="t-meta text-ink-3">{caption.split(" · ")[1]}</p>
      </div>

      {biomarkers.length === 0 ? (
        <p className="t-body-s mt-space-6 text-ink-2">No results carry this flag.</p>
      ) : (
        <>
          {/*
            Wrapped rather than given `hidden md:grid` directly: `.ms-row` sets
            `display: grid` itself, and stacking a display utility on top of it leaves which
            one wins to cascade order.
          */}
          <div className="mt-space-5 hidden md:block">
            {/* Column headings. The compact rows label themselves instead. */}
            <div className="ms-row" aria-hidden="true">
              <span className="t-meta text-ink-3">TEST</span>
              <span className="t-meta text-ink-3">RESULT</span>
              <span className="t-meta text-ink-3">RANGE</span>
              <span className="t-meta text-right text-ink-3">FLAG</span>
            </div>

            {biomarkers.map((biomarker) => (
              <Row key={biomarker.id} biomarker={biomarker} reportId={reportId} />
            ))}
          </div>

          <div className="mt-space-5 md:hidden">
            {biomarkers.map((biomarker) => (
              <Row key={biomarker.id} biomarker={biomarker} reportId={reportId} compact />
            ))}
          </div>
        </>
      )}

      <p className="t-body-s mt-space-5 text-ink-3">{RANGE_NOTE}</p>
    </Card>
  );
}

function Row({
  biomarker,
  reportId,
  compact,
}: {
  biomarker: Biomarker;
  reportId: string;
  compact?: boolean;
}) {
  const { value, unit } = formatValue(biomarker);

  return (
    <BiomarkerRow
      name={biomarker.testName}
      value={value}
      unit={unit}
      range={formatRange(biomarker)}
      flag={biomarker.flag}
      compact={compact}
      biomarkerSlug={biomarker.biomarkerSlug}
      // Ids only. A value or a test name in a URL would end up in history and in any
      // referrer that leaked — see the privacy rules.
      href={
        biomarker.biomarkerSlug
          ? `/biomarkers/${biomarker.biomarkerSlug}?report=${reportId}&b=${biomarker.id}`
          : undefined
      }
    />
  );
}
