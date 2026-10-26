import { cx } from "@/components/ds/cx";
import { DISCLAIMER } from "./nav";

/**
 * The line that has to appear, word for word, on every page that shows results (root
 * CLAUDE.md). A component rather than a string literal per page, so it cannot be paraphrased
 * by accident.
 *
 * Used from 7b, where there are results to carry it.
 */
export function Disclaimer({ className }: { className?: string }) {
  return <p className={cx("t-caption text-ink-3", className)}>{DISCLAIMER}</p>;
}
