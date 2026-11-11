import type { FlagCounts } from "@/lib/api-types";

/**
 * The proportional bar beside a finished report, and the line that says what it means.
 *
 * <strong>The words are not optional.</strong> The design system's rule is that a flag is
 * never shown by colour alone, and a bar of coloured segments is exactly that — so the counts
 * are spelled out underneath, and that line is what a screen reader gets. The bar itself is
 * decoration.
 *
 * Never red: high is orange, low is blue, normal is green (root CLAUDE.md). This is a summary
 * of what the report says, not an alarm about it.
 */

/** High and low first, because they are what the eye is looking for. */
const SEGMENTS = [
  { key: "high", label: "high", className: "ms-flagbar__seg--high" },
  { key: "low", label: "low", className: "ms-flagbar__seg--low" },
  { key: "normal", label: "normal", className: "ms-flagbar__seg--normal" },
  /**
   * Shown, where the mockup's label omits it.
   *
   * A value Medi-Scan could not flag — a qualitative result, or a number OCR read with too
   * little confidence to trust — is still a value on the report. Leaving it out would make
   * "5 tests · 1 high · 3 normal" fail to add up, and the reader who notices is exactly the
   * reader who should be checking that value against the paper.
   */
  { key: "unknown", label: "unchecked", className: "ms-flagbar__seg--unknown" },
] as const;

export function FlagBar({ counts }: { counts: FlagCounts }) {
  const total = counts.total;

  const parts = SEGMENTS.map((segment) => ({
    ...segment,
    count: counts[segment.key],
  })).filter((part) => part.count > 0);

  const label =
    total === 0
      ? "No results found"
      : [`${total} ${total === 1 ? "test" : "tests"}`, ...parts.map((p) => `${p.count} ${p.label}`)].join(
          " · ",
        );

  return (
    <div>
      <div className="ms-flagbar" aria-hidden="true">
        {parts.map((part) => (
          <span
            key={part.key}
            className={`ms-flagbar__seg ${part.className}`}
            style={{ flexGrow: part.count }}
          />
        ))}
      </div>
      <p className="t-caption mt-space-3 text-ink-2">{label}</p>
    </div>
  );
}

/** The processing equivalent: a bar that claims no progress, because there is none to claim. */
export function ProcessingBar({ label }: { label: string }) {
  return (
    <div>
      <div className="ms-flagbar ms-flagbar--idle" aria-hidden="true">
        <span className="ms-flagbar__pending" />
      </div>
      <p className="t-caption mt-space-3 flex items-center gap-space-3 text-ink-2">
        <span className="ms-dot-accent" aria-hidden="true" />
        {label}
      </p>
    </div>
  );
}

/** A failed report: the bar is flat and grey, and the line says what went wrong. */
export function FailedBar({ text }: { text: string }) {
  return (
    <div>
      <div className="ms-flagbar ms-flagbar--idle" aria-hidden="true" />
      <p className="t-caption mt-space-3 text-flag-high-fg">{text}</p>
    </div>
  );
}
