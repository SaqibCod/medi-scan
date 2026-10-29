"use client";

import { useEffect, useState } from "react";

import { Button } from "@/components/ds";
import { useUploadSource } from "@/components/upload/upload-source-context";

/**
 * The phone bar that keeps "Analyze report" reachable.
 *
 * It appears only once the panel has scrolled out of view, because while the panel is on
 * screen the bar would be a second copy of a button already visible.
 *
 * What it does depends on what is ready, and it says which: with a source chosen and consent
 * ticked it submits, otherwise it scrolls back to the panel so the missing step is on screen.
 * It never pretends to submit and then silently do nothing.
 */
export function StickyAnalyzeBar() {
  const { ready, sending, submit } = useUploadSource();
  const [panelOffScreen, setPanelOffScreen] = useState(false);

  useEffect(() => {
    const panel = document.getElementById("upload");
    if (!panel) return;

    const observer = new IntersectionObserver(
      ([entry]) => setPanelOffScreen(!entry.isIntersecting),
      // A sliver of the panel still counts as visible; the bar should not flicker in while
      // the button is a few pixels from the edge.
      { threshold: 0.12 },
    );

    observer.observe(panel);
    return () => observer.disconnect();
  }, []);

  if (!panelOffScreen) return null;

  function onClick() {
    if (ready) {
      submit();
      return;
    }

    const panel = document.getElementById("upload");
    panel?.scrollIntoView({ behavior: "smooth", block: "start" });
  }

  return (
    // Toasts sit above this; ds.css already lifts them on phones.
    <div className="fixed inset-x-0 bottom-0 z-30 border-t border-line bg-bar px-space-4 py-space-3 shadow-[var(--shadow-cta)] backdrop-blur-[var(--blur-bar)] md:hidden">
      <Button variant="accent" block icon="upload" disabled={sending} onClick={onClick}>
        {sending ? "Sending…" : ready ? "Analyze report" : "Analyze a report"}
      </Button>
    </div>
  );
}
