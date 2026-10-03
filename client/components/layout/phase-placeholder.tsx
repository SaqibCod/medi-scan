import type { ReactNode } from "react";

interface PhasePlaceholderProps {
  heading: string;
  /** What this page will do once it is built. Written for a visitor, not a changelog. */
  children: ReactNode;
  /** The build-order phase from `docs/plan.md` section 14 that delivers it. */
  phase: string;
}

/**
 * The shell of a page that is routed but not yet built.
 *
 * Deliberately says so in plain words rather than showing an empty state, a skeleton, or
 * sample data. A fake chart or a placeholder report would be indistinguishable from a broken
 * feature, and worse, in an app about medical results, indistinguishable from real data.
 */
export function PhasePlaceholder({ heading, children, phase }: PhasePlaceholderProps) {
  return (
    <div className="mx-auto max-w-5xl px-4 py-12 sm:px-6 sm:py-16">
      <h1 className="text-balance text-2xl font-semibold tracking-tight sm:text-3xl">{heading}</h1>

      <div className="mt-4 max-w-prose space-y-4 text-pretty leading-relaxed text-muted-foreground">
        {children}
      </div>

      <p className="mt-8 inline-flex rounded-md border border-dashed border-border px-3 py-1.5 text-xs text-muted-foreground">
        Not built yet — arrives in {phase}.
      </p>
    </div>
  );
}
