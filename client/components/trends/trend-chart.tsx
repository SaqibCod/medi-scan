"use client";

import {
  CartesianGrid,
  Line,
  LineChart,
  ReferenceArea,
  ResponsiveContainer,
  Tooltip,
  XAxis,
  YAxis,
} from "recharts";

import type { Flag, TrendPoint } from "@/lib/api-types";
import { formatRefRange, niceScale, spreadTicks } from "@/lib/trends";

/**
 * One marker, one unit, over time (LLD 6).
 *
 * <strong>Every colour is a CSS variable</strong>, written straight into the SVG's `stroke`
 * and `fill`. That is what makes the theme toggle work on a chart: nothing here holds a
 * colour value, so switching themes repaints it with no React involved.
 *
 * <strong>Nothing is interpreted.</strong> The line joins the values the reports gave, the
 * band is the range the latest report printed, and the dots carry the flags the server
 * computed. There is no target, no trendline, no projection, and no word anywhere about
 * whether a direction is good — those are a clinician's to supply, not this app's.
 *
 * Animation is left at Recharts' `"auto"`, which disables it under `prefers-reduced-motion`
 * and during server rendering. Setting it explicitly would override the reader's preference.
 */

const FLAG_COLOUR: Record<Flag, string> = {
  HIGH: "var(--flag-high-fg)",
  LOW: "var(--flag-low-fg)",
  NORMAL: "var(--flag-normal-fg)",
  // A value that could not be checked gets the recessive dot, never a flag colour it has
  // not earned (root CLAUDE.md: never trust an OCR number).
  UNKNOWN: "var(--ink-3)",
};

const MONTHS = ["Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"];

export interface ChartPoint {
  t: number;
  value: number;
  flag: Flag;
  date: string;
  uploaded: boolean;
}

/** A plain date has no time zone, so it is read as three numbers and never as an instant. */
function monthLabel(date: string): string {
  const match = /^(\d{4})-(\d{2})-(\d{2})$/.exec(date);
  if (!match) return date;
  return `${MONTHS[Number(match[2]) - 1]} ${match[1]}`;
}

function dayLabel(date: string): string {
  const match = /^(\d{4})-(\d{2})-(\d{2})$/.exec(date);
  if (!match) return date;
  return `${Number(match[3])} ${MONTHS[Number(match[2]) - 1]} ${match[1]}`;
}

export function toChartPoints(points: TrendPoint[]): ChartPoint[] {
  return points.map((point) => ({
    // Parsed as UTC noon so a date never slips a day for a reader west of Greenwich.
    t: Date.parse(`${point.date}T12:00:00Z`),
    value: point.value,
    flag: point.flag,
    date: point.date,
    uploaded: point.dateSource === "UPLOADED",
  }));
}

/**
 * A dot per point, and the value written beside the last one.
 *
 * Hollow means the date came from the upload rather than the report — the reader is being
 * told the position on the x-axis is approximate, which a filled dot would quietly hide. It
 * is paired with a legend and an asterisk on the axis, so it is never shape alone either.
 *
 * Filled dots carry a 2px ring in the page colour so they stay legible where they sit on the
 * line.
 */
function renderDot(props: {
  cx?: number;
  cy?: number;
  index?: number;
  payload?: ChartPoint;
  lastIndex: number;
}) {
  const { cx, cy, payload, index, lastIndex } = props;
  if (cx === undefined || cy === undefined || !payload) return <g key={`empty-${index}`} />;

  const colour = FLAG_COLOUR[payload.flag] ?? FLAG_COLOUR.UNKNOWN;
  const isLast = index === lastIndex;

  return (
    <g key={`${payload.t}-${index}`}>
      {payload.uploaded ? (
        <circle cx={cx} cy={cy} r={4.5} fill="var(--bg)" stroke={colour} strokeWidth={2} />
      ) : (
        <circle cx={cx} cy={cy} r={4.5} fill={colour} stroke="var(--bg)" strokeWidth={2} />
      )}

      {/* Only the latest point is labelled. A number on every dot goes unread. */}
      {isLast && (
        <text
          x={cx}
          y={cy - 14}
          textAnchor="middle"
          className="ms-chart__value"
          fill="var(--ink)"
        >
          {payload.value}
        </text>
      )}
    </g>
  );
}

function ChartTooltip({
  active,
  payload,
  unit,
}: {
  active?: boolean;
  payload?: { payload: ChartPoint }[];
  unit: string | null;
}) {
  const point = payload?.[0]?.payload;
  if (!active || !point) return null;

  return (
    <div className="ms-charttip">
      <p className="ms-charttip__date">{dayLabel(point.date)}</p>
      <p className="ms-charttip__value">
        {point.value}
        {unit ? ` ${unit}` : ""} · {point.flag}
      </p>
      {point.uploaded && <p className="ms-charttip__note">dated by upload</p>}
    </div>
  );
}

export function TrendChart({
  points,
  unit,
  refLow,
  refHigh,
  describedBy,
  markerName,
}: {
  points: TrendPoint[];
  unit: string | null;
  /** From the latest point only: the range the most recent report printed (LLD 6). */
  refLow: number | null;
  refHigh: number | null;
  /** The id of the table that carries the same numbers, for screen readers. */
  describedBy: string;
  markerName: string;
}) {
  const data = toChartPoints(points);
  const single = data.length === 1;

  // The band has to be inside the plot, so the axis is computed from the values and the
  // bounds together.
  const scale = niceScale([
    ...data.map((point) => point.value),
    ...(refLow !== null ? [refLow] : []),
    ...(refHigh !== null ? [refHigh] : []),
  ]);

  const bandLow = refLow ?? scale.min;
  const bandHigh = refHigh ?? scale.max;
  const hasBand = refLow !== null || refHigh !== null;
  const rangeText = formatRefRange(refLow, refHigh);

  // A lone point would give the time axis a zero-width domain, which has no midpoint to
  // place it at. A day either side puts it in the middle of the plot.
  const day = 86_400_000;
  const domain: [number, number] = single
    ? [data[0].t - day, data[0].t + day]
    : [data[0].t, data[data.length - 1].t];

  const tickPoints = spreadTicks(data);

  return (
    // The name and the description sit on the container, not on the plot. Recharts renders
    // the line as a bare `<path>`, and ARIA attributes are prohibited on an element with no
    // role — so labelling it there is both invalid and, in a screen reader, silent.
    <div
      className="ms-chart"
      role="img"
      aria-label={`${markerName} over time${unit ? `, in ${unit}` : ""}`}
      aria-describedby={describedBy}
    >
      <ResponsiveContainer width="100%" height={240}>
        <LineChart data={data} margin={{ top: 24, right: 16, bottom: 8, left: 4 }}>
          {/* Hairline, solid and recessive: the grid is scenery, not data. */}
          <CartesianGrid stroke="var(--line)" strokeWidth={1} vertical={false} />

          <XAxis
            type="number"
            dataKey="t"
            domain={domain}
            ticks={tickPoints.map((point) => point.t)}
            tickFormatter={(value: number) => {
              const point = data.find((candidate) => candidate.t === value);
              if (!point) return "";
              // The asterisk marks a point positioned by its upload date, which the legend
              // explains. Without it the hollow dot is the only clue, and it is small.
              return `${monthLabel(point.date)}${point.uploaded ? "*" : ""}`;
            }}
            tick={{ fill: "var(--ink-3)" }}
            className="ms-chart__tick"
            axisLine={false}
            tickLine={false}
            padding={{ left: 12, right: 12 }}
          />

          <YAxis
            type="number"
            domain={[scale.min, scale.max]}
            ticks={scale.ticks}
            tick={{ fill: "var(--ink-3)" }}
            className="ms-chart__tick"
            axisLine={false}
            tickLine={false}
            width={44}
          />

          {hasBand && (
            // Both bounds are always given explicitly. A reference area with one end left
            // open extends to a different edge depending on which end it is, and a "normal
            // range" shaded on the wrong side of the line would be worse than none at all.
            <ReferenceArea
              y1={bandLow}
              y2={bandHigh}
              ifOverflow="hidden"
              fill="var(--flag-normal-bg)"
              fillOpacity={1}
              stroke="var(--flag-normal-fg)"
              strokeOpacity={0.55}
              strokeDasharray="3 3"
              label={
                rangeText
                  ? {
                      value: `Range ${rangeText}`,
                      position: "insideTopRight",
                      className: "ms-chart__band",
                      fill: "var(--flag-normal-fg)",
                    }
                  : undefined
              }
            />
          )}

          <Tooltip
            content={<ChartTooltip unit={unit} />}
            cursor={{ stroke: "var(--line-strong)", strokeWidth: 1 }}
          />

          <Line
            type="linear"
            dataKey="value"
            // No line through a single point: there is no change to draw yet.
            stroke={single ? "transparent" : "var(--accent)"}
            strokeWidth={2}
            dot={(props) => renderDot({ ...props, lastIndex: data.length - 1 })}
            activeDot={false}
            // `isAnimationActive` is left at its "auto" default on purpose: it is already
            // off under prefers-reduced-motion and during server rendering, and setting it
            // explicitly would override the reader's own preference.
          />
        </LineChart>
      </ResponsiveContainer>
    </div>
  );
}
