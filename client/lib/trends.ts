"use client";

import { useQuery } from "@tanstack/react-query";

import { api } from "./api";
import type { TrendMarker, TrendPoint, TrendResponse } from "./api-types";
import { useAuth } from "./auth/provider";
import { queryKeys } from "./query-client";

/**
 * Trends (contract 5).
 *
 * Signed-in only, on both endpoints — a guest's reports expire in 24 hours, so there is
 * nothing for them to compare — which is why every query here waits for the auth state rather
 * than firing and collecting a 401.
 */

export function useTrendMarkers() {
  const { state, waiting } = useAuth();
  const signedIn = state.status === "SIGNED_IN";

  return useQuery({
    queryKey: queryKeys.trendMarkers,
    queryFn: ({ signal }) => api.get<TrendMarker[]>("/api/trends/markers", { auth: "auto", signal }),
    enabled: signedIn && !waiting,
  });
}

export function useTrend(marker: string | null) {
  const { state, waiting } = useAuth();
  const signedIn = state.status === "SIGNED_IN";

  return useQuery({
    queryKey: queryKeys.trend(marker ?? ""),
    queryFn: ({ signal }) =>
      api.get<TrendResponse>(`/api/trends?marker=${encodeURIComponent(marker ?? "")}`, {
        auth: "auto",
        signal,
      }),
    enabled: signedIn && !waiting && marker !== null && marker !== "",
  });
}

/**
 * The reference band's label, built from the numbers rather than from text.
 *
 * `TrendPoint` carries `refLow` and `refHigh` but not the range as the lab printed it, so the
 * mockup's "Range <100" and "Range 3.9-5.5" have to be composed here. Deliberately plain: no
 * units, no rounding, no interpretation — these are the two numbers the report gave, in the
 * shape a reader recognises from the report itself.
 */
export function formatRefRange(low: number | null, high: number | null): string | null {
  if (low !== null && high !== null) return `${low}-${high}`;
  if (high !== null) return `<${high}`;
  if (low !== null) return `>${low}`;
  return null;
}

/**
 * The change between the first and last point of one series.
 *
 * A signed difference and nothing more. No "improving", no "worse", no target — the app shows
 * what the reports say, and whether a rise is good or bad is a question for a clinician (root
 * CLAUDE.md, "No medical advice").
 */
export function seriesDelta(points: TrendPoint[]): number | null {
  if (points.length < 2) return null;
  return points[points.length - 1].value - points[0].value;
}

/** "+14", "-0.9", "0" — always carrying its sign, because the sign is the information. */
export function formatDelta(delta: number): string {
  // Trailing zeros from floating-point subtraction read as false precision: 162 - 148 must be
  // "+14", not "+14.000000000000057".
  const rounded = Math.round(delta * 1000) / 1000;
  return rounded > 0 ? `+${rounded}` : String(rounded);
}

/** The series with the most points — what the teaser's sparkline shows (LLD 4). */
export function widestSeries(response: TrendResponse | undefined) {
  if (!response || response.series.length === 0) return null;
  return response.series.reduce((best, next) => (next.points.length > best.points.length ? next : best));
}

export interface Scale {
  min: number;
  max: number;
  ticks: number[];
}

/**
 * A y-axis that lands on round numbers and leaves room for the reference band.
 *
 * Computed here rather than left to the chart library for two reasons. The band has to be
 * drawn with both bounds given explicitly — a reference area with one bound open behaves
 * differently depending on which end is missing, and guessing at that is how a "normal range"
 * ends up shaded on the wrong side of the line. And the extent has to include the reference
 * bounds as well as the values, or a band outside the data's range would be clipped away and
 * the reader would see no band at all.
 *
 * Pure, so the awkward cases — one point, a flat series, a band far from the values — are
 * reachable from a test rather than only from a screenshot.
 */
export function niceScale(values: number[], tickTarget = 5): Scale {
  const finite = values.filter((value) => Number.isFinite(value));
  if (finite.length === 0) return { min: 0, max: 1, ticks: [0, 1] };

  let low = Math.min(...finite);
  let high = Math.max(...finite);

  if (low === high) {
    // A flat series, or a single point. Give it room on both sides so the mark is not
    // pinned to an edge of the plot.
    const pad = Math.abs(low) * 0.1 || 1;
    low -= pad;
    high += pad;
  } else {
    const pad = (high - low) * 0.12;
    low -= pad;
    high += pad;
  }

  const rough = (high - low) / tickTarget;
  const magnitude = 10 ** Math.floor(Math.log10(rough));
  const normalised = rough / magnitude;
  const step = (normalised <= 1 ? 1 : normalised <= 2 ? 2 : normalised <= 5 ? 5 : 10) * magnitude;

  const min = Math.floor(low / step) * step;
  const max = Math.ceil(high / step) * step;

  const ticks: number[] = [];
  for (let tick = min; tick <= max + step / 2; tick += step) {
    // Floating-point addition drifts; these are axis labels and have to read as round.
    ticks.push(Math.round(tick * 1e6) / 1e6);
  }

  return { min, max, ticks };
}

/**
 * At most four x-axis labels, always including the first and the last.
 *
 * A series can carry a hundred points (the API caps it there) and a label under each one is
 * unreadable. The ends are what a reader navigates by; the tooltip carries the rest.
 */
export function spreadTicks<T>(points: T[], limit = 4): T[] {
  if (points.length <= limit) return points;

  const step = (points.length - 1) / (limit - 1);
  return Array.from({ length: limit }, (_, index) => points[Math.round(index * step)]);
}
