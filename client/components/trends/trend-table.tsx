import Link from "next/link";

import { FlagChip } from "@/components/ds";
import type { TrendPoint } from "@/lib/api-types";
import { formatCollectedOn } from "@/lib/report-format";
import { formatRefRange } from "@/lib/trends";

/**
 * The same numbers as a table (LLD 6).
 *
 * Not a fallback — the accessible equivalent, and the only place every value is written
 * down. The chart points at it with `aria-describedby`, and the Chart/Table toggle makes it
 * a choice rather than something hidden behind assistive technology.
 *
 * <strong>Each row carries its own range</strong>, where the chart's band can only show the
 * latest. Labs set their own ranges, so a value that was normal in March against one range
 * and high in September against another is a fact about the ranges, not about the person —
 * and only the table can show that.
 */
export function TrendTable({
  id,
  points,
  unit,
  caption,
}: {
  id: string;
  points: TrendPoint[];
  unit: string | null;
  caption: string;
}) {
  // Newest first: the question a reader arrives with is almost always about the last one.
  const rows = [...points].reverse();

  return (
    <div className="ms-tablewrap">
      <table className="ms-trendtable" id={id}>
        <caption className="sr-only">{caption}</caption>
        <thead>
          <tr>
            <th scope="col">Date</th>
            <th scope="col">Value</th>
            <th scope="col">Range</th>
            <th scope="col">Flag</th>
            <th scope="col">Report</th>
          </tr>
        </thead>
        <tbody>
          {rows.map((point) => {
            const range = formatRefRange(point.refLow, point.refHigh);

            return (
              <tr key={`${point.reportId}-${point.date}`}>
                <td>
                  {formatCollectedOn(point.date)}
                  {point.dateSource === "UPLOADED" && (
                    <span className="t-caption block text-ink-3">dated by upload</span>
                  )}
                </td>
                <td className="t-data">
                  {point.value}
                  {unit ? <span className="text-ink-3"> {unit}</span> : null}
                </td>
                <td className="t-data text-ink-2">{range ?? "—"}</td>
                <td>
                  <FlagChip flag={point.flag} />
                </td>
                <td>
                  <Link href={`/reports/${point.reportId}`} className="underline">
                    Open
                  </Link>
                </td>
              </tr>
            );
          })}
        </tbody>
      </table>
    </div>
  );
}
