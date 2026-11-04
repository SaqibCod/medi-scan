"use client";

import { useMemo, useState } from "react";

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

        <SummaryCard result={result} />

        <CountTiles counts={result.counts} value={filter} onChange={setFilter} />

        <OcrNote textSource={result.textSource} />

        <ResultsTable
          biomarkers={shown}
          reportId={report.id}
          caption={`${filterLabel(filter)} · ${shown.length} of ${result.counts.total}`}
        />

        <Disclaimer extra={RESULTS_DISCLAIMER_EXTRA} />

        {/* Phones get the panel as its own view; this is the way in (report-page--06). */}
        <Button
          variant="accent"
          className="lg:hidden"
          onClick={() => setChatOpen(true)}
        >
          {CHAT_COPY.openOnPhone}
        </Button>
      </div>

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

      <div className="flex-1 overflow-hidden rounded-xl border border-hairline bg-surface">
        <ChatPanel reportId={reportId} result={result} variant="phone" />
      </div>
    </div>
  );
}
