import { cx } from "./cx";

export interface StatusPillProps {
  status: "online" | "waking";
  /** Drops the trailing "UP…" so it fits a phone bar. */
  short?: boolean;
}

/**
 * Backend health for the top bar.
 *
 * `role="status"` makes it a polite live region, so going from waking to online is announced
 * rather than silently redrawn. The dot always sits beside the word, so the state never
 * depends on colour.
 */
export function StatusPill({ status, short }: StatusPillProps) {
  const waking = status === "waking";

  return (
    <span className={cx("ms-status", waking && "ms-status--waking")} role="status">
      <span className="ms-status__dot" />
      {waking ? (short ? "STARTING" : "STARTING UP…") : "ONLINE"}
    </span>
  );
}
