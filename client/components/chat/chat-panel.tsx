"use client";

import { useEffect, useRef, useState } from "react";

import { IconButton, Tag } from "@/components/ds";
import { CHAT_COPY, chatErrorCopy } from "@/lib/copy";
import { useChat, type ChatMessage } from "@/lib/chat";
import type { ReportResult } from "@/lib/api-types";
import { AnswerMarkdown } from "./answer-markdown";
import { suggestionsFor } from "./suggestions";

const MAX_QUESTION = 500;

/**
 * The chat panel (LLD 10.2).
 *
 * One component for both breakpoints: the desktop column and the phone's full-screen view
 * differ in their frame and their helper line, not in how asking works. Two implementations
 * would be two places for the Stop button to get out of step.
 *
 * Everything it holds is React state for this report in this tab, and it is gone on reload —
 * which is what the "Not saved" tag promises.
 */
export function ChatPanel({
  reportId,
  result,
  variant,
}: {
  reportId: string;
  result: ReportResult;
  variant: "desktop" | "phone";
}) {
  // The draft lives in the hook: a recoverable failure puts the question back in the box
  // (LLD 10.4), and the hook is what knows a failure happened.
  const { messages, status, error, draft, setDraft, ask, stop, unblock } = useChat(reportId);

  const listRef = useRef<HTMLDivElement>(null);
  const suggestions = suggestionsFor(result);

  // Keep the newest message in view as it streams in. Assigning `scrollTop` rather than
  // calling `scrollTo`, which jsdom does not implement — it throws there, and an effect that
  // throws takes the whole results view down with it in every test that renders this panel.
  useEffect(() => {
    const list = listRef.current;
    if (list) list.scrollTop = list.scrollHeight;
  }, [messages]);

  const countdown = useCountdown(
    error?.code === "RATE_LIMITED" ? (error.retryAt ?? null) : null,
    unblock,
  );
  const failure = error ? chatErrorCopy(error.code, countdown ?? undefined) : null;
  const blocked = status === "blocked";
  const streaming = status === "streaming";

  function send(question: string) {
    if (blocked || streaming) return;
    setDraft("");
    void ask(question);
  }

  return (
    <section
      className="flex h-full flex-col"
      aria-label={CHAT_COPY.title}
    >
      <header className="flex items-center justify-between gap-space-3 border-b border-hairline px-space-5 py-space-4">
        <h2 className="text-[17px] font-medium text-ink">{CHAT_COPY.title}</h2>
        <Tag>{CHAT_COPY.notSaved}</Tag>
      </header>

      <div ref={listRef} className="ms-chat flex-1 overflow-y-auto px-space-5 py-space-4">
        {messages.length === 0 ? (
          <EmptyState suggestions={suggestions} onPick={send} disabled={blocked} />
        ) : (
          messages.map((message) => <Bubble key={message.id} message={message} reportId={reportId} />)
        )}

        {failure ? (
          <p
            role="status"
            className="mt-space-3 text-[13px] leading-[19px] text-ink-3"
            data-testid="chat-error"
          >
            {failure.text}
          </p>
        ) : null}
      </div>

      <footer className="border-t border-hairline px-space-5 py-space-4">
        <label htmlFor="chat-question" className="sr-only">
          {CHAT_COPY.inputLabel}
        </label>
        <div className="flex items-end gap-space-3">
          <textarea
            id="chat-question"
            value={draft}
            disabled={blocked}
            maxLength={MAX_QUESTION}
            rows={1}
            placeholder={blocked ? failure?.placeholder : CHAT_COPY.placeholder}
            onChange={(event) => setDraft(event.target.value)}
            onKeyDown={(event) => {
              // Enter sends, Shift+Enter is a new line. A question is usually one line, and
              // reaching for a send button every time would be the wrong default.
              if (event.key === "Enter" && !event.shiftKey) {
                event.preventDefault();
                send(draft);
              }
            }}
            className="min-h-[40px] flex-1 resize-none rounded-lg bg-surface-2 px-space-4 py-space-3 text-[14px] text-ink placeholder:text-ink-3 disabled:opacity-60"
          />

          {streaming ? (
            <button
              type="button"
              onClick={stop}
              className="rounded-lg px-space-4 py-space-3 text-[13px] text-ink-2 hover:text-ink"
            >
              {CHAT_COPY.stop}
            </button>
          ) : (
            <IconButton
              icon="arrow-right"
              label={CHAT_COPY.send}
              onClick={() => send(draft)}
              disabled={blocked || draft.trim().length === 0}
            />
          )}
        </div>

        <div className="mt-space-3 flex items-start justify-between gap-space-4">
          <p className="text-[12px] leading-[18px] text-ink-3">
            {variant === "phone" ? CHAT_COPY.helperPhone : CHAT_COPY.helperDesktop}
          </p>
          <span className="shrink-0 font-mono text-[12px] text-ink-3">
            {draft.length}/{MAX_QUESTION}
          </span>
        </div>
      </footer>
    </section>
  );
}

function EmptyState({
  suggestions,
  onPick,
  disabled,
}: {
  suggestions: string[];
  onPick: (question: string) => void;
  disabled: boolean;
}) {
  return (
    <div className="flex flex-col gap-space-4">
      <p className="text-[14px] leading-[21px] text-ink-2">{CHAT_COPY.emptyPrompt}</p>
      <div className="flex flex-col items-start gap-space-3">
        {suggestions.map((question) => (
          <button
            key={question}
            type="button"
            disabled={disabled}
            onClick={() => onPick(question)}
            className="rounded-full border border-hairline px-space-4 py-space-3 text-left text-[14px] text-ink hover:bg-surface-2 disabled:opacity-60"
          >
            {question}
          </button>
        ))}
      </div>
    </div>
  );
}

/** One message, with whatever note its finish reason calls for. */
function Bubble({ message, reportId }: { message: ChatMessage; reportId: string }) {
  if (message.role === "user") {
    return <div className="ms-bubble ms-bubble--user">{message.text}</div>;
  }

  return (
    <div className="flex flex-col items-start gap-space-2">
      <div className="ms-bubble ms-bubble--assistant">
        <AnswerMarkdown text={message.text} />
      </div>

      {message.stopped ? (
        <p className="text-[12px] text-ink-3">{CHAT_COPY.stopped}</p>
      ) : null}

      {message.finishReason === "DECLINED" ? (
        <p className="text-[12px] text-ink-3">{CHAT_COPY.declinedNote}</p>
      ) : null}

      {message.finishReason === "NOT_IN_CONTEXT" ? (
        <p className="text-[12px] text-ink-3">{CHAT_COPY.notInContextNote}</p>
      ) : null}

      {message.sources && message.sources.length > 0 ? (
        <p className="text-[12px] text-ink-3">
          {CHAT_COPY.sourcesPrefix} {CHAT_COPY.sourcesReport}
          {message.sources.map((source) => (
            <span key={source.slug}>
              {" · "}
              <a
                className="underline"
                href={`/biomarkers/${source.slug}?report=${reportId}`}
              >
                {source.title}
              </a>
            </span>
          ))}
        </p>
      ) : null}
    </div>
  );
}

/**
 * Counts a rate limit down, and re-enables the input when it reaches zero.
 *
 * The countdown is the whole point of the row: "ask again later" with no number is a dead
 * end, and a reader has no way to know whether later means seconds or an hour.
 */
function useCountdown(deadline: number | null, onExpired: () => void): string | null {
  // The deadline is computed where the error is made, not here: reading the clock during a
  // render is impure, and a countdown derived from a fixed instant also stays honest when the
  // tab is backgrounded and the interval stops firing.
  const [now, setNow] = useState(() => Date.now());

  useEffect(() => {
    if (deadline === null) return;
    const timer = setInterval(() => setNow(Date.now()), 1000);
    return () => clearInterval(timer);
  }, [deadline]);

  const left = deadline === null ? null : Math.max(0, Math.ceil((deadline - now) / 1000));

  useEffect(() => {
    if (left === 0) onExpired();
  }, [left, onExpired]);

  if (left === null || left <= 0) return null;
  const minutes = Math.floor(left / 60);
  const rest = left % 60;
  return minutes > 0 ? `${minutes} min ${rest} s` : `${rest} s`;
}
