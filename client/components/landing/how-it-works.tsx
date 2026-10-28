"use client";

import { useState } from "react";

import { IconButton, StepTimeline, type Step } from "@/components/ds";
import { copy } from "@/lib/copy";

/**
 * The six steps, with the OCR-dependent wording switched (B4).
 *
 * Steps 2 to 6 are the same five the processing page shows, worded identically on purpose:
 * someone who read them here should recognise them while they wait.
 */
const STEPS: Step[] = [
  { title: "Upload", body: copy.steps.upload },
  { title: "Read", body: copy.steps.read },
  { title: "Mask", body: copy.steps.mask },
  { title: "Extract", body: copy.steps.extract },
  { title: "Flag", body: copy.steps.flag },
  { title: "Explain", body: copy.steps.explain },
];

export function HowItWorks() {
  const [active, setActive] = useState(0);
  const step = STEPS[active];

  return (
    <section id="how" aria-labelledby="how-heading" className="scroll-mt-[96px] py-space-9">
      <div className="flex items-start justify-between gap-space-6">
        <h2 id="how-heading" className="t-display-m max-w-[14ch] text-ink">
          How Medi-Scan
          <br />
          reads your report
        </h2>

        <div className="hidden shrink-0 items-center gap-space-2 md:flex">
          <IconButton
            icon="chevron-left"
            label="Previous step"
            onClick={() => setActive((i) => (i - 1 + STEPS.length) % STEPS.length)}
          />
          <IconButton
            icon="chevron-right"
            label="Next step"
            onClick={() => setActive((i) => (i + 1) % STEPS.length)}
          />
        </div>
      </div>

      {/* Desktop: the active step's body above a horizontal rail. */}
      <div className="hidden md:block">
        <p className="t-body-l ml-auto mt-space-8 max-w-[52ch] text-ink-2">
          <span className="t-meta mr-space-3 text-ink-3">{`0${active + 1}`}</span>
          <span className="text-ink">{step.title}.</span> {step.body}
        </p>

        <div className="mt-space-8">
          <StepTimeline steps={STEPS} active={active} onSelect={setActive} />
        </div>
      </div>

      {/* Phone: the active step expands in place. */}
      <div className="mt-space-7 md:hidden">
        <StepTimeline steps={STEPS} active={active} onSelect={setActive} orientation="vertical" />
      </div>
    </section>
  );
}
