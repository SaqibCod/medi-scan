"use client";

import { useId } from "react";

import { Eyebrow, FlagChip, Icon } from "@/components/ds";
import type { TrendPoint } from "@/lib/api-types";
import { TRENDS_COPY } from "@/lib/copy";
import { formatCollectedOn } from "@/lib/report-format";
import { formatDelta, seriesDelta } from "@/lib/trends";
import { TrendChart } from "./trend-chart";
import { TrendTable } from "./trend-table";

/**
 * One unit's worth of a marker: the headline number, the change, and the chart or the table.
 *
 * The header states only what is in the data — the latest value, its flag as the server
 * computed it, and the plain signed difference between the first and last point of *this*
 * series. No ideal value, no target, and no word about whether a direction is welcome.
 */
export function SeriesCard({
  markerName,
  unit,
  points,
  view,
}: {
  markerName: string;
  unit: string | null;
  points: TrendPoint[];
  view: "chart" | "table";
}) {
  const tableId = useId();

  if (points.length === 0) return null;

  const latest = points[points.length - 1];
  const first = points[0];
  const delta = seriesDelta(points);
  const single = points.length === 1;

  return (
    <section className="ms-seriescard">
      <header className="ms-seriescard__head">
        <div>
          <Eyebrow>{TRENDS_COPY.inUnit(unit)}</Eyebrow>
          <p className="mt-space-3 flex flex-wrap items-center gap-space-3">
            <span className="t-numeral-xl leading-none text-ink">{latest.value}</span>
            {unit && <span className="t-body text-ink-3">{unit}</span>}
            <FlagChip flag={latest.flag} />
          </p>
        </div>

        <div className="ms-seriescard__meta">
          {single || delta === null ? (
            <p className="t-caption text-ink-2">
              {TRENDS_COPY.latestFrom(formatCollectedOn(latest.date))}
            </p>
          ) : (
            <>
              <p className="t-caption text-ink-2">
                {TRENDS_COPY.since(formatDelta(delta), unit, formatCollectedOn(first.date))}
              </p>
              <p className="t-caption text-ink-3">{TRENDS_COPY.reports(points.length)}</p>
            </>
          )}
        </div>
      </header>

      {view === "chart" ? (
        <>
          <TrendChart
            points={points}
            unit={unit}
            // The band is the range on the most recent report, and only that one. Earlier
            // reports may have used different ranges; the table carries each of them.
            refLow={latest.refLow}
            refHigh={latest.refHigh}
            describedBy={tableId}
            markerName={markerName}
          />

          <div className="ms-chartlegend">
            {(latest.refLow !== null || latest.refHigh !== null) && (
              <span className="ms-chartlegend__item">
                <span className="ms-chartlegend__band" aria-hidden="true" />
                {TRENDS_COPY.legendBand}
              </span>
            )}
            {points.some((point) => point.dateSource === "UPLOADED") && (
              <span className="ms-chartlegend__item">
                <span className="ms-chartlegend__hollow" aria-hidden="true" />
                {TRENDS_COPY.legendHollow}
              </span>
            )}
          </div>

          {single && <p className="t-caption mt-space-4 text-ink-2">{TRENDS_COPY.onePoint(unit)}</p>}

          {/*
            The chart points at this with `aria-describedby`, so it has to be in the document
            even while the chart is the chosen view. Visually hidden, never `display: none`.
          */}
          <div className="sr-only">
            <TrendTable
              id={tableId}
              points={points}
              unit={unit}
              caption={`${markerName}${unit ? ` in ${unit}` : ""}, every value`}
            />
          </div>
        </>
      ) : (
        <TrendTable
          id={tableId}
          points={points}
          unit={unit}
          caption={`${markerName}${unit ? ` in ${unit}` : ""}, every value`}
        />
      )}
    </section>
  );
}

/** The notice above the cards when one marker came back in more than one unit. */
export function MultiUnitNotice({ markerName, count }: { markerName: string; count: number }) {
  return (
    <p className="ms-multiunit">
      <span className="shrink-0 text-accent" aria-hidden="true">
        <Icon name="chart" size={18} />
      </span>
      {TRENDS_COPY.multiUnit(markerName, count)}
    </p>
  );
}
