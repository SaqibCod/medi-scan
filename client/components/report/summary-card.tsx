import { Card, Eyebrow, FlagChip } from "@/components/ds";
import { flagForHighlight } from "@/lib/report-format";
import type { ReportResult } from "@/lib/api-types";

/**
 * The model's summary, rendered as plain text.
 *
 * React text nodes and nothing else: no markdown, no `dangerouslySetInnerHTML`, no HTML of
 * any kind. This string came from a language model that read a document somebody uploaded,
 * which makes it the least trusted content on the page — and tokens live in browser storage,
 * so an injection here would be worth having.
 *
 * Highlight chips are derived (B8): highlights are plain strings in the contract, so the
 * only honest way to colour one is to find the flagged marker it names.
 */
export function SummaryCard({ result }: { result: ReportResult }) {
  const highlights = result.highlights ?? [];

  return (
    <Card>
      <Eyebrow>Summary</Eyebrow>

      <p className="t-body-l mt-space-4 max-w-[62ch] text-ink">{result.summary}</p>

      {highlights.length > 0 && (
        <ul className="mt-space-6">
          {highlights.map((highlight) => {
            const flag = flagForHighlight(highlight, result.biomarkers);

            return (
              <li
                key={highlight}
                className="flex items-start gap-space-4 border-t border-line py-space-4"
              >
                {flag && (
                  <span className="shrink-0">
                    <FlagChip flag={flag} />
                  </span>
                )}
                <span className="t-body-s text-ink-2">{highlight}</span>
              </li>
            );
          })}
        </ul>
      )}
    </Card>
  );
}
