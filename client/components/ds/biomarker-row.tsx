import Link from "next/link";

import { FlagChip } from "./flag-chip";
import { cx } from "./cx";
import type { Flag } from "@/lib/api-types";

export interface BiomarkerRowProps {
  name: string;
  value: string;
  unit?: string | null;
  range?: string | null;
  flag: Flag | string;
  /** Stacks the range under the name, for phones. */
  compact?: boolean;
  /** When the API gives one, the name links to the curated explainer page. */
  biomarkerSlug?: string | null;
  /**
   * The exact link to use instead of the one derived from the slug.
   *
   * The report page passes `?report=…&b=…` so the explainer can show an "On your report"
   * box. Only ids go in that URL, never a value — see the privacy rules. Ignored unless
   * `biomarkerSlug` is set, so a row with no curated page can never become a link.
   */
  href?: string;
}

/** Missing values show an em dash rather than an empty cell, so the column still reads. */
const EMPTY = "—";

/**
 * One row of the results table.
 *
 * Values and ranges are set in the mono styles so digits line up down the column, which is
 * most of what makes a table of lab results readable at a glance.
 */
export function BiomarkerRow({
  name,
  value,
  unit,
  range,
  flag,
  compact,
  biomarkerSlug,
  href,
}: BiomarkerRowProps) {
  const label = biomarkerSlug ? (
    <Link href={href ?? `/biomarkers/${biomarkerSlug}`} className="ms-row__link">
      {name}
    </Link>
  ) : (
    name
  );

  if (compact) {
    return (
      <div className="ms-row ms-row--compact">
        <span className="ms-row__name">
          <span>{label}</span>
          <span className="ms-row__range">Range {range || EMPTY}</span>
        </span>
        <span className="ms-row__value" style={{ whiteSpace: "nowrap" }}>
          {value} <span className="ms-row__unit">{unit}</span>
        </span>
        <FlagChip flag={flag} />
      </div>
    );
  }

  return (
    <div className={cx("ms-row")}>
      <span>{label}</span>
      <span className="ms-row__value">
        {value} <span className="ms-row__unit">{unit}</span>
      </span>
      <span className="ms-row__range">{range || EMPTY}</span>
      <FlagChip flag={flag} />
    </div>
  );
}
