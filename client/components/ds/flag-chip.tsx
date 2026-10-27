import type { Flag } from "@/lib/api-types";

export interface FlagChipProps {
  /** Taken straight from the API. Anything unrecognised renders as UNKNOWN. */
  flag: Flag | string;
}

const KNOWN = ["LOW", "NORMAL", "HIGH"] as const;

/**
 * A biomarker's flag.
 *
 * HIGH is orange, LOW is blue, NORMAL is green, and **never red** — a flag is information
 * copied off the report, not an alarm (root CLAUDE.md). The word is always shown, so the
 * meaning never depends on colour. There is deliberately no CRITICAL.
 *
 * Unknown values render a neutral dash, as the contract requires clients to do with enum
 * values they do not recognise. The dash gets a real accessible name, because "—" read aloud
 * is nothing at all.
 */
export function FlagChip({ flag }: FlagChipProps) {
  const upper = String(flag ?? "").toUpperCase();
  const known = (KNOWN as readonly string[]).includes(upper);
  const resolved = known ? upper : "UNKNOWN";

  return (
    <span className={`ms-flag ms-flag--${resolved.toLowerCase()}`}>
      {known ? (
        resolved
      ) : (
        <>
          <span aria-hidden="true">—</span>
          <span className="sr-only">Flag unknown</span>
        </>
      )}
    </span>
  );
}
