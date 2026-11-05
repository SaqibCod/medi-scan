"use client";

import { useEffect, useLayoutEffect, useMemo, useRef, useState } from "react";

import { ChatPanel } from "@/components/chat/chat-panel";
import { Button, IconButton } from "@/components/ds";
import { Disclaimer } from "@/components/shell/disclaimer";
import { CHAT_COPY, RESULTS_DISCLAIMER_EXTRA } from "@/lib/copy";
import { formatCollectedOn } from "@/lib/report-format";
import { CountTiles, filterLabel, type CountFilter } from "./count-tiles";
import { GuestExpiryNote } from "./guest-expiry-note";
import { OcrNote } from "./ocr-note";
import { ReportHeader } from "./report-header";
import { ResultsTable } from "./results-table";
import { SummaryCard } from "./summary-card";
import type { ReportResponse, ReportResult } from "@/lib/api-types";

/**
 * The done view.
 *
 * Two columns on desktop now that chat exists: results on the left, the panel sticky on the
 * right, as `report-page--01` and `--04` show. Until Phase 5 this was one centred column,
 * because the second column's only purpose was to hold "Ask about this report" and an empty
 * half would have looked like something failed to load.
 *
 * On a phone the panel is a separate full-screen view reached by a button under the results
 * (`--06`, `--07`), rather than a third thing stacked below a long table. Leaving the
 * results mounted behind it keeps the scroll position for the trip back.
 */
export function ResultsView({
  report,
  onDelete,
}: {
  report: ReportResponse;
  onDelete: () => void;
}) {
  const [filter, setFilter] = useState<CountFilter>(null);
  const [chatOpen, setChatOpen] = useState(false);
  const result = report.result;

  const summaryRef = useRef<HTMLDivElement>(null);
  const scrolledPastSummary = useScrolledPast(summaryRef);

  /**
   * Where the reader was before the chat view took over.
   *
   * The phone chat is a view swap, not an overlay, so the results come back freshly mounted
   * at the top of the document. Someone who opened chat from halfway down a long table would
   * otherwise have to find their place again, which is exactly the cost the floating button
   * exists to avoid.
   */
  const scrollBeforeChat = useRef(0);
  const hasOpenedChat = useRef(false);

  function openChat() {
    scrollBeforeChat.current = window.scrollY;
    hasOpenedChat.current = true;
    setChatOpen(true);
    // The chat view is a screen of its own; starting it part way down reads as broken.
    window.scrollTo(0, 0);
  }

  // Restored in a layout effect so it happens before paint - a visible jump from the top of
  // the report back down to where the reader was is worse than no restore at all. Guarded on
  // having actually opened chat, so a first mount never fights the browser's own restore.
  useLayoutEffect(() => {
    if (!chatOpen && hasOpenedChat.current) {
      window.scrollTo(0, scrollBeforeChat.current);
    }
  }, [chatOpen]);

  const shown = useMemo(
    () =>
      filter === null
        ? (result?.biomarkers ?? [])
        : (result?.biomarkers ?? []).filter((b) => b.flag === filter),
    [result, filter],
  );

  if (!result) return null;

  if (chatOpen) {
    return (
      <PhoneChatView reportId={report.id} result={result} onBack={() => setChatOpen(false)} />
    );
  }

  return (
    <div className="mx-auto flex w-full max-w-[1240px] gap-space-7 py-space-8 lg:items-start">
      <div className="flex min-w-0 flex-1 flex-col gap-space-7">
        <ReportHeader report={report} onDelete={onDelete} />

        <GuestExpiryNote expiresAt={report.expiresAt} />

        {/* Wrapped so the floating button knows when the summary has gone past. */}
        <div ref={summaryRef}>
          <SummaryCard result={result} />
        </div>

        <CountTiles counts={result.counts} value={filter} onChange={setFilter} />

        <OcrNote textSource={result.textSource} />

        <ResultsTable
          biomarkers={shown}
          reportId={report.id}
          caption={`${filterLabel(filter)} · ${shown.length} of ${result.counts.total}`}
        />

        <Disclaimer extra={RESULTS_DISCLAIMER_EXTRA} />

        {/*
          Phones get the panel as its own view; this is the way in (report-page--06).

          The breakpoint lives on a wrapper rather than on the Button. `.ms-btn` sets its own
          `display`, and because ds.css loads after the Tailwind utilities it wins on source
          order - so `lg:hidden` on the button itself did nothing and the button showed up
          beside the desktop panel. A plain div has no competing rule.
        */}
        <div className="lg:hidden">
          <Button variant="accent" block onClick={openChat}>
            {CHAT_COPY.openOnPhone}
          </Button>
        </div>
      </div>

      {/*
        The floating way in, for a report long enough that the button at the foot is a scroll
        away. Only once the summary has gone past: while it is still on screen the reader is
        reading, and a button over the text they are reading is in the way rather than handy.
      */}
      {scrolledPastSummary ? (
        <div
          data-testid="floating-ask"
          className="fixed inset-x-0 bottom-space-5 z-20 flex justify-center px-space-5 lg:hidden"
        >
          <Button variant="accent" size="sm" icon="sources" onClick={openChat}>
            {CHAT_COPY.openOnPhone}
          </Button>
        </div>
      ) : null}

      <aside className="sticky top-space-7 hidden h-[calc(100vh-8rem)] w-[380px] shrink-0 overflow-hidden rounded-xl border border-hairline bg-surface lg:block">
        <ChatPanel reportId={report.id} result={result} variant="desktop" />
      </aside>
    </div>
  );
}

/**
 * The phone view (report-page--07, --11).
 *
 * Full screen, with the report's own line at the top so the reader can see which report they
 * are asking about without going back for it.
 */
function PhoneChatView({
  reportId,
  result,
  onBack,
}: {
  reportId: string;
  result: ReportResult;
  onBack: () => void;
}) {
  const high = result.counts.high;
  const collected = result.collectedOn ? `collected ${formatCollectedOn(result.collectedOn)}` : null;
  const context = [collected, high > 0 ? `${high} high` : null].filter(Boolean).join(" · ");

  return (
    <div className="flex min-h-[calc(100vh-4rem)] flex-col">
      <div className="flex items-center gap-space-3 py-space-4">
        <IconButton icon="chevron-left" label={CHAT_COPY.back} onClick={onBack} />
        <p className="text-[13px] text-ink-2">{context}</p>
      </div>

      {/*
        A flex column, not just a flex item. The panel inside sizes itself with `h-full`, and
        `h-full` needs a parent with a definite height - a `min-h` on the outer wrapper does
        not provide one. Without this the input floated directly under the last message
        instead of sitting at the bottom of the screen, as report-page--07 shows it.
      */}
      <div className="flex min-h-0 flex-1 flex-col overflow-hidden rounded-xl border border-hairline bg-surface">
        <ChatPanel reportId={reportId} result={result} variant="phone" />
      </div>
    </div>
  );
}

/**
 * Whether the page has scrolled past `ref`.
 *
 * An IntersectionObserver rather than a scroll listener: the browser does the work off the
 * main thread and fires twice rather than on every frame, which matters on a phone that is
 * also streaming an answer.
 *
 * "Past" means above the viewport, not merely out of it — scrolling back up above the summary
 * (which cannot happen here, but could if the layout changed) should not count. The observer
 * reports the bounding rect, so the sign of its bottom edge answers that directly.
 */
function useScrolledPast(ref: React.RefObject<HTMLElement | null>) {
  const [past, setPast] = useState(false);

  useEffect(() => {
    const element = ref.current;
    if (!element || typeof IntersectionObserver === "undefined") return;

    const observer = new IntersectionObserver(
      ([entry]) => setPast(!entry.isIntersecting && entry.boundingClientRect.bottom < 0),
      { threshold: 0 },
    );
    observer.observe(element);
    return () => observer.disconnect();
  }, [ref]);

  return past;
}
