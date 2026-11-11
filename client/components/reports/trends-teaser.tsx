"use client";

import Link from "next/link";

import { Eyebrow } from "@/components/ds";
import type { TrendMarker } from "@/lib/api-types";
import { REPORTS_COPY } from "@/lib/copy";
import { useTrend, widestSeries } from "@/lib/trends";

/**
 * "2 markers appear in more than one report." (mockup `my-reports-page--01`)
 *
 * Shown to signed-in readers only, and only when there is actually something to compare. Up
 * to two markers, because the point is to suggest that Trends exists, not to be Trends.
 */

/**
 * A sparkline: a line and its last point, nothing else.
 *
 * Hand-drawn SVG rather than a chart library. It has no axes, no ticks, no tooltip and no
 * legend — it is a glyph the size of a word, and reaching for Recharts here would ship a
 * charting runtime to a page that does not otherwise need one. The real charts on `/trends`
 * are a different matter and use it.
 *
 * Colour comes from `currentColor`, so the card's own token drives it and both themes work.
 */
function Sparkline({ values }: { values: number[] }) {
  if (values.length < 2) return null;

  const width = 72;
  const height = 24;
  const low = Math.min(...values);
  const high = Math.max(...values);
  // A flat series would divide by zero; drawing it down the middle is the honest picture.
  const span = high - low || 1;

  const points = values.map((value, index) => {
    const x = (index / (values.length - 1)) * width;
    const y = height - ((value - low) / span) * height;
    return [x, y] as const;
  });

  const last = points[points.length - 1];

  return (
    <svg
      width={width}
      height={height}
      viewBox={`0 0 ${width} ${height}`}
      className="ms-spark"
      aria-hidden="true"
      focusable="false"
    >
      <polyline
        points={points.map(([x, y]) => `${x},${y}`).join(" ")}
        fill="none"
        stroke="currentColor"
        strokeWidth="1.6"
        strokeLinecap="round"
        strokeLinejoin="round"
      />
      <circle cx={last[0]} cy={last[1]} r="2.6" fill="currentColor" />
    </svg>
  );
}

function MarkerCard({ marker }: { marker: TrendMarker }) {
  const { data } = useTrend(marker.marker);
  const series = widestSeries(data);
  const points = series?.points ?? [];

  const first = points[0];
  const last = points[points.length - 1];
  const unit = series?.unit;

  return (
    <Link href={`/trends?marker=${encodeURIComponent(marker.marker)}`} className="ms-teasercard">
      <span className="ms-teasercard__spark text-accent">
        <Sparkline values={points.map((point) => point.value)} />
      </span>

      <span className="ms-teasercard__body">
        <span className="t-body-s text-ink">{marker.displayName}</span>
        <span className="t-caption mt-space-2 block text-ink-3">
          {REPORTS_COPY.trendsTeaser.reports(marker.reportCount)}
          {first && last && (
            <>
              {" · "}
              {first.value} → {last.value}
              {unit ? ` ${unit}` : ""}
            </>
          )}
        </span>
      </span>
    </Link>
  );
}

export function TrendsTeaser({ markers }: { markers: TrendMarker[] }) {
  if (markers.length === 0) return null;

  return (
    <section className="ms-teaser">
      <div>
        <Eyebrow>{REPORTS_COPY.trendsTeaser.eyebrow}</Eyebrow>
        <p className="t-title mt-space-3 text-ink">
          {REPORTS_COPY.trendsTeaser.headline(markers.length)}
        </p>
        {/* `ink-2`, not `faint`: at 22px this is body-sized text and has to clear 4.5:1,
            which `faint` only manages against the display sizes it was chosen for. */}
        <p className="t-title text-ink-2">{REPORTS_COPY.trendsTeaser.subhead}</p>
      </div>

      <div className="ms-teaser__cards">
        {markers.slice(0, 2).map((marker) => (
          <MarkerCard key={marker.marker} marker={marker} />
        ))}
      </div>
    </section>
  );
}
