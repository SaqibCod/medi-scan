"use client";

import { useRef, useState } from "react";

import { Button, Card, SampleCard } from "@/components/ds";
import { InlineNotice } from "@/components/notices/inline-notice";
import { useSamples } from "@/lib/reports";
import { useUploadSource } from "@/components/upload/upload-source-context";
import type { Sample } from "@/lib/api-types";

/**
 * The three bundled synthetic reports.
 *
 * "Try it" selects rather than submits (B6): the contract needs `consent: true` for a sample
 * too, so the card hands the choice up to the upload panel, which already owns the consent
 * box and the button. A card that posted straight away would be the one path through the app
 * that skipped the consent the other two require.
 *
 * `GET /api/samples` needs no credential, so opening the landing page still creates no guest
 * session.
 */
export function SamplesSection() {
  const { data: samples, isPending, isError, refetch } = useSamples();
  const { source, chooseSample } = useUploadSource();

  const selectedId = source.kind === "sample" ? source.sample.id : null;

  function onPick(id: string) {
    const sample = samples?.find((candidate) => candidate.id === id);
    if (!sample) return;

    chooseSample(sample);
    const panel = document.getElementById("upload");
    panel?.scrollIntoView({ behavior: "smooth", block: "start" });
  }

  return (
    <section id="samples" aria-labelledby="samples-heading" className="scroll-mt-[96px] py-space-9">
      <div className="flex flex-col gap-space-5 md:flex-row md:items-end md:justify-between">
        <h2 id="samples-heading" className="t-display-m text-ink">
          No report handy?
          <br />
          <span className="text-faint">Try a sample.</span>
        </h2>
        <p className="t-body-s max-w-[40ch] text-ink-2">
          Synthetic reports with made-up values, so you can see the whole flow without
          uploading anything.
        </p>
      </div>

      <div className="mt-space-8">
        {isPending && <SampleSkeletons />}

        {isError && (
          <InlineNotice
            title="We couldn't load the sample reports."
            action={
              <Button variant="outline" size="sm" onClick={() => void refetch()}>
                Try again
              </Button>
            }
          >
            <p>
              Uploading your own report still works. If the server is waking up, this usually
              fixes itself in a moment.
            </p>
          </InlineNotice>
        )}

        {samples && (
          <SampleRow
            samples={samples}
            selectedId={selectedId}
            onPick={onPick}
          />
        )}
      </div>
    </section>
  );
}

/**
 * The cards: three across on desktop, a swipe row with pager dots on phones.
 *
 * The row scrolls inside its own box rather than bleeding past the gutters. An earlier
 * version used negative margins to run the cards to the screen edge, which made the row
 * wider than the page and gave the whole document a horizontal scrollbar at 390px — the one
 * thing the layout rules say must never happen.
 */
function SampleRow({
  samples,
  selectedId,
  onPick,
}: {
  samples: Sample[];
  selectedId: string | null;
  onPick: (id: string) => void;
}) {
  const [current, setCurrent] = useState(0);
  const rowRef = useRef<HTMLUListElement>(null);

  function onScroll() {
    const row = rowRef.current;
    if (!row) return;

    // Which card is nearest the left edge. Good enough for dots, and no observer needed.
    const card = row.firstElementChild as HTMLElement | null;
    const step = card ? card.offsetWidth + 16 : row.clientWidth;
    setCurrent(Math.round(row.scrollLeft / step));
  }

  return (
    <>
      <ul
        ref={rowRef}
        onScroll={onScroll}
        className="flex snap-x snap-mandatory gap-space-4 overflow-x-auto pb-space-4 md:grid md:grid-cols-3 md:overflow-visible md:pb-0"
      >
        {samples.map((sample, index) => (
          <li key={sample.id} className="w-72.5 shrink-0 snap-start md:w-auto">
            <SampleCard
              num={`${index < 9 ? "0" : ""}${index + 1}`}
              title={sample.title}
              description={sample.description}
              markers={`${sample.markerCount} markers`}
              selected={selectedId === sample.id}
              cta={selectedId === sample.id ? "Selected" : "Try it"}
              onClick={() => onPick(sample.id)}
            />
          </li>
        ))}
      </ul>

      {/* Decorative: the row is already reachable by keyboard and reads in order. */}
      <div className="mt-space-2 flex justify-center gap-space-2 md:hidden" aria-hidden="true">
        {samples.map((sample, index) => (
          <span
            key={sample.id}
            className={`h-1.5 w-1.5 rounded-full ${index === current ? "bg-accent" : "bg-line-strong"}`}
          />
        ))}
      </div>
    </>
  );
}

/** Three cards' worth of shape, so the section does not jump when they arrive. */
function SampleSkeletons() {
  return (
    <ul className="grid gap-space-4 md:grid-cols-3" aria-hidden="true">
      {[0, 1, 2].map((i) => (
        <li key={i}>
          <Card>
            <div className="flex h-[232px] flex-col justify-between">
              <div className="h-5 w-24 rounded-full bg-surface-2" />
              <div className="flex flex-col gap-space-3">
                <div className="h-4 w-2/3 rounded-sm bg-surface-2" />
                <div className="h-3 w-full rounded-sm bg-surface-2" />
              </div>
              <div className="h-10 w-28 rounded-sm bg-surface-2" />
            </div>
          </Card>
        </li>
      ))}
    </ul>
  );
}
