import { describe, expect, it } from "vitest";

import { suggestionsFor } from "./suggestions";
import type { Biomarker, ReportResult } from "@/lib/api-types";

/**
 * The suggestion chips (LLD 10.2, 13).
 *
 * Built from the report rather than hard-coded, and the reason is worth a test: a chip naming
 * a test the reader does not have invites a question the answer can only decline as not
 * covered, which makes the feature look broken on its first use.
 */
describe("suggestionsFor", () => {
  const marker = (testName: string, flag: Biomarker["flag"]): Biomarker => ({
    id: testName,
    testName,
    rawValue: "1",
    numericValue: 1,
    unit: "mg/dL",
    referenceRangeText: "<2",
    refLow: null,
    refHigh: 2,
    flag,
    biomarkerSlug: null,
  });

  const result = (biomarkers: Biomarker[]): ReportResult => ({
    collectedOn: "2026-09-28",
    textSource: "TEXT_LAYER",
    biomarkers,
    summary: "",
    highlights: [],
    counts: { total: biomarkers.length, low: 0, normal: 0, high: 0, unknown: 0 },
  });

  it("opens with the first flagged marker", () => {
    const chips = suggestionsFor(
      result([marker("Total Cholesterol", "NORMAL"), marker("LDL Cholesterol", "HIGH")]),
    );

    expect(chips[0]).toBe("Why is my LDL Cholesterol flagged?");
  });

  it("falls back to the first marker when nothing is flagged", () => {
    const chips = suggestionsFor(result([marker("TSH", "NORMAL"), marker("Free T4", "NORMAL")]));

    expect(chips[0]).toBe("What does TSH measure?");
  });

  it("always offers four chips", () => {
    expect(suggestionsFor(result([marker("TSH", "NORMAL")]))).toHaveLength(4);
  });

  it("always includes the one the app will refuse", () => {
    // Deliberate: a reader who was going to ask about medication finds the boundary at once,
    // rather than after typing a paragraph.
    expect(suggestionsFor(result([marker("TSH", "HIGH")]))).toContain(
      "Should I take medication for this?",
    );
  });

  it("does not name the same marker in two chips", () => {
    const chips = suggestionsFor(
      result([
        marker("LDL Cholesterol", "HIGH"),
        marker("HDL Cholesterol", "NORMAL"),
      ]),
    );

    expect(chips[2]).toBe("What does HDL Cholesterol measure?");
  });

  it("copes with a report that has no markers", () => {
    // Should not happen on a DONE report, but an empty list must not produce "undefined".
    const chips = suggestionsFor(result([]));

    expect(chips).toHaveLength(4);
    expect(chips.join(" ")).not.toContain("undefined");
  });
});
