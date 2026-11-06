import { cx } from "@/components/ds/cx";
import { DISCLAIMER } from "./nav";

/**
 * The line that has to appear, word for word, on every page that shows results (root
 * CLAUDE.md). A component rather than a string literal per page, so it cannot be paraphrased
 * by accident.
 *
 * Used from 7b, where there are results to carry it.
 */
export function Disclaimer({ className, extra }: { className?: string; extra?: string }) {
  return (
    <p className={cx("t-caption text-ink-3", className)}>
      {/*
        `extra` is appended, never substituted. A results page may add a sentence — the
        mockup does — but the mandated line always comes first and always intact, which a
        `children` prop would have allowed someone to replace.
      */}
      {DISCLAIMER}
      {extra ? ` ${extra}` : null}
    </p>
  );
}
