import { Info } from "lucide-react";

/**
 * The educational-use disclaimer, from `docs/plan.md` section 1.
 *
 * Shown on every page, not tucked into a footer or behind a dismiss button. An app that
 * explains lab results has to keep saying it is not a clinician, and a banner the user can
 * dismiss stops saying it.
 */
export function DisclaimerBanner() {
  return (
    <aside
      // `role="note"` rather than `role="alert"`: this is standing context, not a live
      // announcement, so it must not interrupt a screen reader mid-sentence on every
      // navigation.
      role="note"
      aria-label="Medical disclaimer"
      className="border-b border-amber-500/25 bg-amber-50 text-amber-950 dark:bg-amber-950/40 dark:text-amber-100"
    >
      <div className="mx-auto flex max-w-5xl items-start gap-2.5 px-4 py-2.5 sm:px-6">
        <Info aria-hidden="true" className="mt-0.5 size-4 shrink-0" />
        <p className="min-w-0 text-pretty text-xs leading-relaxed sm:text-sm">
          For education only — not medical advice, diagnosis, or treatment. Always talk to a
          qualified clinician about your results.
        </p>
      </div>
    </aside>
  );
}
