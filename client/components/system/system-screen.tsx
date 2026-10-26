import type { ReactNode } from "react";

import { Eyebrow, Helix, Icon } from "@/components/ds";
import { PageContainer } from "@/components/shell/page-container";
import { cx } from "@/components/ds/cx";

export interface SystemScreenProps {
  /** Two to four words naming the situation. Also carries the status in words. */
  eyebrow: string;
  /**
   * `info` is violet, for a situation that is nobody's fault — a report that expired, a guest
   * session that ran out. `warn` is orange, for a refusal or a fault: 403, 500, 503. Orange,
   * never red: the design system keeps red out of the product entirely.
   */
  tone?: "info" | "warn";
  /** The statement. */
  headline: string;
  /** The second half, set in `faint`. Softens the statement without a second paragraph. */
  headlineFaint?: string;
  children: ReactNode;
  /** Short facts under a hairline, each with a marker. Optional. */
  notes?: ReactNode[];
  /** Buttons, in order of what we expect someone to want. */
  actions?: ReactNode;
  /** 404, 401, 403, 500, 503. */
  numeral: string;
  /** Extra content between the body and the actions — the request id block, the attempt line. */
  aside?: ReactNode;
}

/**
 * The frame every system screen shares (mockups `system-screens--01` to `--06`).
 *
 * The numeral is `aria-hidden`: "503" read aloud is not an explanation, and the eyebrow
 * already says what happened in words. On a phone it moves above the eyebrow, which is what
 * `--08` and `--09` do — the figure is the first thing you see on a small screen, and the
 * text then reads top to bottom without it in the way.
 */
export function SystemScreen({
  eyebrow,
  tone = "info",
  headline,
  headlineFaint,
  children,
  notes,
  actions,
  numeral,
  aside,
}: SystemScreenProps) {
  return (
    <PageContainer className="py-space-9 md:py-space-10">
      <div className="grid items-center gap-space-7 lg:grid-cols-[minmax(0,1fr)_minmax(0,0.9fr)]">
        {/* The numeral comes first in the source so it is first on a phone; on desktop the
            grid puts it back on the right. */}
        <div className="relative flex items-center justify-center lg:order-2 lg:min-h-[420px]">
          <div className="pointer-events-none absolute inset-0 hidden items-center justify-center lg:flex">
            <Helix width={420} height={620} />
          </div>
          <p className="ms-sysnum relative" aria-hidden="true">
            {numeral}
          </p>
        </div>

        <div className="lg:order-1">
          <Eyebrow
            style={{ color: tone === "warn" ? "var(--flag-high-fg)" : undefined }}
          >
            {eyebrow}
          </Eyebrow>

          <h1 className="t-display-m mt-space-4 max-w-[18ch] text-ink">
            {headline}
            {headlineFaint && (
              <>
                {" "}
                <span className="text-faint">{headlineFaint}</span>
              </>
            )}
          </h1>

          <div className="t-body mt-space-5 max-w-[56ch] text-ink-2">{children}</div>

          {aside && <div className="mt-space-5">{aside}</div>}

          {actions && <div className="mt-space-7 flex flex-wrap gap-space-3">{actions}</div>}

          {notes && notes.length > 0 && (
            <ul className="mt-space-7 flex flex-col">
              {notes.map((note, i) => (
                <li
                  key={i}
                  className={cx(
                    "flex items-start gap-space-3 border-t border-line py-space-4",
                    i === notes.length - 1 && "border-b",
                  )}
                >
                  <span className="mt-[2px] shrink-0 text-accent" aria-hidden="true">
                    <Icon name="plus" size={16} />
                  </span>
                  <span className="t-body-s text-ink-2">{note}</span>
                </li>
              ))}
            </ul>
          )}
        </div>
      </div>
    </PageContainer>
  );
}
