import type { ReactNode } from "react";

import { cx } from "@/components/ds/cx";

/**
 * The content column.
 *
 * Max width 1360px, gutters clamped from `space-8` on desktop down to `space-4` on phones
 * (design system, "Space, shape and layout"). Everything that is not full-bleed sits in one
 * of these, so the gutters are declared once rather than guessed per page.
 */
export function PageContainer({
  children,
  className,
}: {
  children: ReactNode;
  className?: string;
}) {
  return (
    <div className={cx("mx-auto w-full max-w-340 px-space-4 md:px-space-8", className)}>
      {children}
    </div>
  );
}
