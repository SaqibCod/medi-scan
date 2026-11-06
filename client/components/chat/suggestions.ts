import type { ReportResult } from "@/lib/api-types";

/**
 * The four suggestion chips, built from the report (LLD 10.2).
 *
 * Built rather than hard-coded, because a chip naming a test the reader does not have is
 * worse than no chip: it invites a question the answer can only decline as not covered. The
 * mockup's "Why is my LDL flagged?" is the shape, not the text — on a thyroid report the
 * first chip names a thyroid marker.
 *
 * The fourth chip is deliberately one the app will refuse. It is the honest way to show the
 * boundary: a reader who is going to ask about medication anyway finds out immediately that
 * this is not what the tool does, rather than after typing a paragraph.
 */
export function suggestionsFor(result: ReportResult): string[] {
  const biomarkers = result.biomarkers ?? [];
  const flagged = biomarkers.find((b) => b.flag === "HIGH" || b.flag === "LOW");
  const first = biomarkers[0];

  const opener = flagged
    ? `Why is my ${flagged.testName} flagged?`
    : first
      ? `What does ${first.testName} measure?`
      : "What does this report cover?";

  // A different marker from the opener, so the two chips do not ask about the same test.
  const second = biomarkers.find((b) => b.testName !== flagged?.testName && b !== first);

  return [
    opener,
    "Which results are out of range?",
    second ? `What does ${second.testName} measure?` : "What do these results mean together?",
    "Should I take medication for this?",
  ];
}
