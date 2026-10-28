"use client";

import { Icon } from "@/components/ds";
import { useUploadSource } from "./upload-source-context";

/**
 * What the panel shows once a sample card has been picked (B6).
 *
 * It replaces the tabs rather than sitting beside them, because a sample and a file are the
 * same slot: there is one thing being analyzed, and showing a drop zone next to a chosen
 * sample would leave it ambiguous which one the button is about.
 *
 * "Change" clears it and hands the slot back to the tabs.
 */
export function SelectedSample() {
  const { source, clearSource } = useUploadSource();
  if (source.kind !== "sample") return null;

  const { title, markerCount } = source.sample;

  return (
    <div className="flex items-center justify-between gap-space-4 rounded-md border border-line-strong bg-surface-2 px-space-5 py-space-4">
      <span className="flex min-w-0 items-center gap-space-3">
        <span className="flex h-11 w-11 shrink-0 items-center justify-center rounded-sm bg-accent-soft text-accent-ink">
          <Icon name="file" size={20} />
        </span>
        <span className="min-w-0">
          <span className="t-body block truncate text-ink">Sample: {title}</span>
          <span className="t-meta text-ink-3">
            {markerCount} {markerCount === 1 ? "marker" : "markers"}
          </span>
        </span>
      </span>

      <button
        type="button"
        onClick={clearSource}
        className="t-body-s shrink-0 text-accent-ink underline underline-offset-4 focus-visible:outline-2 focus-visible:outline-offset-[3px] focus-visible:outline-accent"
      >
        Change
      </button>
    </div>
  );
}
