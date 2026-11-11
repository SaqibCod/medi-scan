import { describe, expect, it } from "vitest";

import type { TrendPoint, TrendResponse } from "./api-types";
import { formatDelta, formatRefRange, niceScale, seriesDelta, spreadTicks, widestSeries } from "./trends";

/**
 * The arithmetic behind the charts (LLD 10, unit test 9).
 *
 * Pure functions, tested directly, because every one of them has a case that is invisible in
 * a screenshot: a flat series, a single point, a range with one end missing, a difference
 * that floating-point arithmetic renders as `14.000000000000057`.
 */

function point(over: Partial<TrendPoint> & Pick<TrendPoint, "value">): TrendPoint {
  return {
    reportId: "r1",
    date: "2026-03-14",
    dateSource: "COLLECTED",
    refLow: null,
    refHigh: null,
    flag: "NORMAL",
    ...over,
  };
}

describe("the reference range label", () => {
  it("reads as a range when the report printed both ends", () => {
    expect(formatRefRange(3.9, 5.5)).toBe("3.9-5.5");
  });

  it("reads as an upper limit when that is all there was", () => {
    expect(formatRefRange(null, 100)).toBe("<100");
  });

  it("reads as a lower limit when that is all there was", () => {
    expect(formatRefRange(60, null)).toBe(">60");
  });

  it("is absent when the report printed no range at all", () => {
    // Which is how the chart knows to draw no band, rather than inventing one.
    expect(formatRefRange(null, null)).toBeNull();
  });
});

describe("the change across a series", () => {
  it("is the plain difference between the first and last points", () => {
    expect(seriesDelta([point({ value: 148 }), point({ value: 155 }), point({ value: 162 })])).toBe(14);
  });

  it("is negative when the value came down, and says so", () => {
    expect(formatDelta(seriesDelta([point({ value: 1.1 }), point({ value: 0.21 })]) ?? 0)).toBe("-0.89");
  });

  it("always carries its sign, because the sign is the information", () => {
    expect(formatDelta(14)).toBe("+14");
    expect(formatDelta(0)).toBe("0");
  });

  it("does not render floating-point noise as precision", () => {
    // 162 - 148 in binary floating point is not exactly 14.
    const delta = seriesDelta([point({ value: 147.9 }), point({ value: 162.0 })]);
    expect(formatDelta(delta ?? 0)).toBe("+14.1");
  });

  it("is absent for a single point, because there is no change yet", () => {
    expect(seriesDelta([point({ value: 5 })])).toBeNull();
    expect(seriesDelta([])).toBeNull();
  });
});

describe("the y axis", () => {
  it("lands on round numbers around the values", () => {
    const scale = niceScale([148, 155, 162]);

    expect(scale.min).toBeLessThanOrEqual(148);
    expect(scale.max).toBeGreaterThanOrEqual(162);
    expect(scale.ticks[0]).toBe(scale.min);
    expect(scale.ticks[scale.ticks.length - 1]).toBe(scale.max);
    // Round, not arbitrary: every tick is a multiple of the step.
    const step = scale.ticks[1] - scale.ticks[0];
    scale.ticks.forEach((tick) => expect(Math.abs(tick % step)).toBeLessThan(1e-6));
  });

  it("includes the reference bounds, so a band outside the values still fits", () => {
    // Values 148-162 with a limit at 100: without the bound in the extent the band would be
    // clipped away and the reader would see no range at all.
    const scale = niceScale([148, 155, 162, 100]);

    expect(scale.min).toBeLessThanOrEqual(100);
  });

  it("gives a flat series room rather than pinning it to an edge", () => {
    const scale = niceScale([5, 5, 5]);

    expect(scale.min).toBeLessThan(5);
    expect(scale.max).toBeGreaterThan(5);
  });

  it("survives a single point", () => {
    const scale = niceScale([97]);

    expect(scale.min).toBeLessThan(97);
    expect(scale.max).toBeGreaterThan(97);
    expect(scale.ticks.length).toBeGreaterThan(1);
  });

  it("survives having nothing at all", () => {
    expect(niceScale([])).toEqual({ min: 0, max: 1, ticks: [0, 1] });
  });

  it("handles values below zero and across it", () => {
    const scale = niceScale([-3, 4]);
    expect(scale.min).toBeLessThanOrEqual(-3);
    expect(scale.max).toBeGreaterThanOrEqual(4);
  });
});

describe("the x-axis labels", () => {
  it("keeps every point when there are few", () => {
    expect(spreadTicks([1, 2, 3])).toEqual([1, 2, 3]);
  });

  it("thins a long series down to four, keeping both ends", () => {
    const points = Array.from({ length: 100 }, (_, index) => index);
    const ticks = spreadTicks(points);

    expect(ticks).toHaveLength(4);
    expect(ticks[0]).toBe(0);
    expect(ticks[3]).toBe(99);
  });
});

describe("picking a series for the sparkline", () => {
  it("takes the one with the most points", () => {
    const response: TrendResponse = {
      marker: "glucose",
      displayName: "Glucose",
      series: [
        { unit: "mmol/L", points: [point({ value: 5.4 })] },
        { unit: "mg/dL", points: [point({ value: 92 }), point({ value: 104 }), point({ value: 97 })] },
      ],
    };

    expect(widestSeries(response)?.unit).toBe("mg/dL");
  });

  it("is absent when there is nothing to draw", () => {
    expect(widestSeries(undefined)).toBeNull();
    expect(widestSeries({ marker: "m", displayName: "M", series: [] })).toBeNull();
  });
});
