"use client";

import { cx } from "@/components/ds/cx";
import type { Flag, FlagCounts } from "@/lib/api-types";

/** `null` is "All results". The others map straight onto `result.counts`. */
export type CountFilter = null | Flag;

const TILES: Array<{ filter: CountFilter; label: string; key: keyof FlagCounts }> = [
  { filter: null, label: "All results", key: "total" },
  { filter: "HIGH", label: "High", key: "high" },
  { filter: "NORMAL", label: "Normal", key: "normal" },
  { filter: "LOW", label: "Low", key: "low" },
  // "Unclear" rather than "Unknown": the value is not unclear to the lab, only to us.
  { filter: "UNKNOWN", label: "Unclear", key: "unknown" },
];

/** The label colour per flag. Never red — a flag is information, not an alarm. */
const LABEL_COLOUR: Record<string, string> = {
  HIGH: "text-flag-high-fg",
  NORMAL: "text-flag-normal-fg",
  LOW: "text-flag-low-fg",
};

/**
 * The five counts, which also work as a filter.
 *
 * Counts come from `result.counts` rather than from counting the rows: the server computed
 * them, and a second count in the browser is a second thing that can disagree.
 *
 * Rendered as radios because that is what they are — one of five, exactly one selected —
 * which gets arrow-key navigation and the right announcement for free.
 */
export function CountTiles({
  counts,
  value,
  onChange,
}: {
  counts: FlagCounts;
  value: CountFilter;
  onChange: (filter: CountFilter) => void;
}) {
  return (
    <div role="radiogroup" aria-label="Filter results by flag" className="grid grid-cols-5 gap-space-2 md:gap-space-3">
      {TILES.map((tile) => {
        const selected = tile.filter === value;
        const count = counts[tile.key];

        return (
          <button
            key={tile.label}
            type="button"
            role="radio"
            aria-checked={selected}
            // An empty group is not a filter worth offering, but it still has to be readable.
            disabled={count === 0 && tile.filter !== null}
            onClick={() => onChange(tile.filter)}
            className={cx(
              "flex flex-col items-start rounded-md border px-space-3 py-space-4 text-left transition-colors md:px-space-5",
              "focus-visible:outline-2 focus-visible:outline-offset-[3px] focus-visible:outline-accent",
              selected ? "border-accent bg-accent-soft" : "border-line hover:border-line-strong",
              count === 0 && tile.filter !== null && "opacity-45",
            )}
          >
            <span className="t-numeral-xl leading-none text-ink">{count}</span>
            <span
              className={cx(
                "t-body-s mt-space-2",
                tile.filter ? (LABEL_COLOUR[tile.filter] ?? "text-ink-2") : "text-ink-2",
              )}
            >
              {tile.label}
            </span>
          </button>
        );
      })}
    </div>
  );
}

/** The caption above the table: "High · 2 of 5". */
export function filterLabel(filter: CountFilter): string {
  return TILES.find((tile) => tile.filter === filter)?.label ?? "All results";
}
