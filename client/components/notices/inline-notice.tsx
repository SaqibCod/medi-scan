import type { ReactNode } from "react";

import { Icon, type IconName } from "@/components/ds";
import { cx } from "@/components/ds/cx";

export interface InlineNoticeProps {
  tone?: "warn" | "info";
  icon?: IconName;
  /** The first sentence, in weight 500. Says what happened. */
  title: ReactNode;
  /** What to do next. */
  children?: ReactNode;
  /** The code line, in `meta` style: "413 FILE_TOO_LARGE". For a person reporting a problem. */
  code?: string;
  /** An action that undoes or works around the problem. */
  action?: ReactNode;
  /**
   * Interrupts a screen reader instead of waiting for a pause.
   *
   * Off by default, because most notices appear beside something the reader is already
   * looking at. On for the upload errors (LLD 5.4): the file was just rejected, focus is
   * about to move here, and finding out at the next natural pause is too late to stop
   * someone pressing "Analyze report" again.
   */
  assertive?: boolean;
}

/**
 * A notice placed next to the thing it is about — under the drop zone, under the last chat
 * message — so the question or the file is still on screen while it is read.
 *
 * `warn` is orange. Nothing here is ever red: an upload that was too large is information,
 * not an emergency.
 */
export function InlineNotice({
  tone = "warn",
  icon,
  title,
  children,
  code,
  action,
  assertive = false,
}: InlineNoticeProps) {
  return (
    <div
      className={cx("ms-notice", `ms-notice--${tone}`)}
      role={assertive ? "alert" : "status"}
    >
      <span className="ms-notice__icon" aria-hidden="true">
        <Icon name={icon ?? (tone === "warn" ? "file" : "clock")} size={16} />
      </span>

      <div className="min-w-0 flex-1">
        <b>{title}</b>
        {children}
        {action && <div className="mt-space-3">{action}</div>}
        {code && <p className="ms-notice__code">{code}</p>}
      </div>
    </div>
  );
}
