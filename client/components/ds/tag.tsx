import type { ReactNode } from "react";

import { cx } from "./cx";

export interface TagProps {
  /** Default is an outlined label; the tones are the filled count chips over a report. */
  tone?: "count" | "high" | "normal" | "low";
  children: ReactNode;
}

/** Small pill label for counts and categories: "5 markers", "2 high". */
export function Tag({ tone, children }: TagProps) {
  return <span className={cx("ms-tag", tone && `ms-tag--${tone}`)}>{children}</span>;
}
