"use client";

import { useCallback, useEffect, useRef, useState } from "react";

import { credentialHeaders } from "./api";
import { ApiError, networkError, toApiError } from "./api-error";
import { getBackendStatusChecker } from "./backend-status";
import { API_URL } from "./env";
import { handleSessionInvalid } from "./guest-session";
import { publishDailyLimit } from "./notices";
import {
  isSigningIn,
  streamFinished,
  streamStarted,
  subscribeSignIn,
} from "./sign-in-gate";
import { createSseParser } from "./sse";
import type { ChatTurn } from "./api-types";

/**
 * Chat streaming, and the state behind the panel (LLD 10.3).
 *
 * Nothing here is persisted. The conversation is React state belonging to one report in one
 * tab: no `localStorage`, no `sessionStorage`, no URL, no page title, no console. It does not
 * survive a reload, and the panel's "Not saved" tag is literally true on both ends of the
 * connection — the server stores nothing either (contract 4.4).
 */

/** The contract's three finish reasons (4.4). */
export type FinishReason = "COMPLETE" | "DECLINED" | "NOT_IN_CONTEXT";

export interface SourcePage {
  slug: string;
  title: string;
  heading: string;
}

export interface ChatMessage {
  id: string;
  role: "user" | "assistant";
  text: string;
  /** Set once `done` arrives. Absent while streaming, and on a failed answer. */
  finishReason?: FinishReason;
  sources?: SourcePage[];
  /** The reader pressed Stop. The partial answer stays, marked. */
  stopped?: boolean;
}

/**
 * What the panel is doing, which decides whether the input accepts anything.
 *
 * `signing-in` is separate from `blocked` because it ends on its own and says something
 * different: the reader is not out of quota, they are mid-sign-in and asking will work again
 * in a moment (7c).
 */
export type ChatStatus = "idle" | "streaming" | "blocked" | "signing-in";

export interface ChatError {
  code: string;
  /**
   * When the caller may ask again, as an epoch milliseconds instant, or null.
   *
   * An instant rather than the server's "seconds from now", and computed once here when the
   * error arrives. The countdown then ticks against a fixed point instead of recomputing
   * `Date.now()` while rendering — which React's compiler rightly refuses, and which would
   * also drift if the tab were backgrounded and the timer stopped firing.
   */
  retryAt: number | null;
}

/** Turns the client keeps and sends back. Stopped and failed answers are not among them. */
const MAX_HISTORY_TURNS = 6;

/**
 * An unknown `finishReason` is treated as COMPLETE.
 *
 * Contract 9.2's rule for unknown enum values. A future reason the client has never heard of
 * should render as an ordinary answer rather than as nothing.
 */
function asFinishReason(value: unknown): FinishReason {
  return value === "DECLINED" || value === "NOT_IN_CONTEXT" ? value : "COMPLETE";
}

/** Events the client does not recognise are ignored, per the contract. */
interface StreamHandlers {
  onMeta?: (meta: { answerId: string; cached: boolean }) => void;
  onToken: (text: string) => void;
  onSources: (sources: SourcePage[], usedReport: boolean) => void;
  onDone: (finishReason: FinishReason) => void;
  onError: (error: ChatError) => void;
}

/**
 * Opens the stream and drives the handlers.
 *
 * A POST with a JSON body and a credential header, which is why the browser's `EventSource`
 * is no use — it is GET-only and sends no headers. `fetch` plus a reader it is.
 */
export async function streamChat(
  reportId: string,
  question: string,
  history: ChatTurn[],
  signal: AbortSignal,
  handlers: StreamHandlers,
): Promise<void> {
  const headers = await credentialHeaders("guest");

  let response: Response;
  try {
    response = await fetch(`${API_URL}/api/reports/${reportId}/chat`, {
      method: "POST",
      headers: { ...headers, "Content-Type": "application/json", Accept: "text/event-stream" },
      body: JSON.stringify({ question, history }),
      credentials: "omit",
      signal,
    });
  } catch {
    // A caller-initiated abort is Stop being pressed, not a failure to report as one.
    if (signal.aborted) return;
    getBackendStatusChecker().reportNetworkFailure();
    throw networkError();
  }

  if (!response.ok) {
    // Before the stream: ordinary Problem Details, exactly as the contract promises, so the
    // same `code` switch the rest of the app uses works here too.
    const error = await toApiError(response);
    if (error.code === "CAPACITY") publishDailyLimit(error.retryAfterSec);
    if (error.code === "SESSION_INVALID") handleSessionInvalid(error);
    throw error;
  }

  getBackendStatusChecker().reportSuccess();

  const body = response.body;
  if (!body) throw networkError();

  const reader = body.getReader();
  const decoder = new TextDecoder();
  const parser = createSseParser();

  const deliver = (frames: { event: string; data: string }[]) => {
    for (const frame of frames) {
      let payload: unknown;
      try {
        payload = JSON.parse(frame.data);
      } catch {
        // A malformed frame is not worth tearing the answer down for; the guard runs on the
        // server and the rest of the stream is still valid.
        continue;
      }
      const data = payload as Record<string, unknown>;

      switch (frame.event) {
        case "meta":
          handlers.onMeta?.({
            answerId: String(data.answerId ?? ""),
            cached: data.cached === true,
          });
          break;
        case "token":
          handlers.onToken(String(data.text ?? ""));
          break;
        case "sources":
          handlers.onSources(
            Array.isArray(data.pages) ? (data.pages as SourcePage[]) : [],
            data.report === true,
          );
          break;
        case "done":
          handlers.onDone(asFinishReason(data.finishReason));
          break;
        case "error":
          handlers.onError({ code: String(data.code ?? "INTERNAL_ERROR"), retryAt: null });
          break;
        default:
          // Unknown event names are ignored (contract 4.4).
          break;
      }
    }
  };

  try {
    for (;;) {
      const { done, value } = await reader.read();
      if (done) break;
      deliver(parser.push(decoder.decode(value, { stream: true })));
    }
    deliver(parser.flush());
  } catch {
    if (signal.aborted) return;
    getBackendStatusChecker().reportNetworkFailure();
    throw networkError();
  } finally {
    reader.releaseLock();
  }
}

/**
 * The panel's state machine.
 *
 * Returns the conversation, a sender, and a stopper. The question is held back from the
 * message list until the request has actually been accepted for the cases where the contract
 * says it should be returned to the input — a rate limit, a provider outage, a dropped
 * connection — because a reader who has to retype what they just typed will not bother.
 */
export function useChat(reportId: string) {
  const [messages, setMessages] = useState<ChatMessage[]>([]);
  const [status, setStatus] = useState<ChatStatus>("idle");
  const [error, setError] = useState<ChatError | null>(null);

  /**
   * The text in the box.
   *
   * Owned here rather than by the panel, because this hook is what decides a question should
   * be given back — the contract's recoverable failures put it in the input instead of making
   * the reader retype it. With the draft outside, that meant mirroring a "returned question"
   * into the panel's state from an effect, which is a cascading render and a lint error for
   * good reason. Here it is one plain assignment in the failure branch.
   */
  const [draft, setDraft] = useState("");

  const controller = useRef<AbortController | null>(null);

  /**
   * Whether a sign-in is running (7c).
   *
   * Mirrored into state so the panel re-renders when it changes; the gate itself is a plain
   * module so that nothing in chat has to import anything from auth, or the reverse.
   */
  const [signingIn, setSigningIn] = useState(false);

  useEffect(() => subscribeSignIn(() => setSigningIn(isSigningIn())), []);

  /**
   * The last turns, oldest first, capped at the contract's six.
   *
   * Stopped and failed answers are left out: a truncated answer is not what the assistant
   * would have said, and feeding it back would have the model continue from a sentence it
   * never finished.
   */
  const historyFrom = useCallback((list: ChatMessage[]): ChatTurn[] => {
    const usable = list.filter(
      (message) => message.role === "user" || (!message.stopped && message.finishReason),
    );
    return usable.slice(-MAX_HISTORY_TURNS).map((message) => ({
      role: message.role,
      content: message.text,
    }));
  }, []);

  const stop = useCallback(() => {
    controller.current?.abort();
    controller.current = null;
    setStatus("idle");
    setMessages((list) => {
      const last = list.at(-1);
      if (!last || last.role !== "assistant" || last.finishReason) return list;
      return [...list.slice(0, -1), { ...last, stopped: true }];
    });
  }, []);

  const ask = useCallback(
    async (rawQuestion: string) => {
      const question = rawQuestion.trim();
      if (!question || status !== "idle") return;

      // Asking is refused while a sign-in is running (7c). A question sent now would carry
      // whichever credential happened to be current, and could arrive as a guest a moment
      // after this report stopped being a guest's - a 404 for a report on screen.
      if (isSigningIn()) {
        setStatus("signing-in");
        return;
      }

      setError(null);
      setDraft("");

      const history = historyFrom(messages);
      const answerId = crypto.randomUUID();

      setMessages((list) => [
        ...list,
        { id: crypto.randomUUID(), role: "user", text: question },
        { id: answerId, role: "assistant", text: "" },
      ]);
      setStatus("streaming");

      const abort = new AbortController();
      controller.current = abort;

      const patch = (change: Partial<ChatMessage>) =>
        setMessages((list) =>
          list.map((message) => (message.id === answerId ? { ...message, ...change } : message)),
        );

      // Registered so a sign-in can wait for this answer rather than cutting it off: 7c
      // awaits whenNoStreamActive() before swapping the credential.
      streamStarted();

      try {
        await streamChat(reportId, question, history, abort.signal, {
          onToken: (text) =>
            setMessages((list) =>
              list.map((message) =>
                message.id === answerId ? { ...message, text: message.text + text } : message,
              ),
            ),
          onSources: (sources) => patch({ sources }),
          onDone: (finishReason) => patch({ finishReason }),
          onError: (streamError) => {
            // Mid-stream. The partial answer stays on screen — it is what the model actually
            // said, and hiding it would lose information the reader already saw arriving.
            setError(streamError);
            if (streamError.code === "CAPACITY") {
              publishDailyLimit(null);
              setStatus("blocked");
            }
          },
        });
      } catch (caught) {
        const failure =
          caught instanceof ApiError
            ? {
                code: caught.code,
                retryAt: caught.retryAfterSec ? Date.now() + caught.retryAfterSec * 1000 : null,
              }
            : { code: "NETWORK_ERROR", retryAt: null };

        setError(failure);

        // Remove the empty assistant bubble: no answer ever started, so an empty one would
        // read as the model having nothing to say.
        setMessages((list) => list.filter((message) => message.id !== answerId));

        // The contract's recoverable cases give the question back rather than making the
        // reader retype it. The user bubble goes too: the question was never asked, so
        // leaving it on screen would imply an answer is still coming.
        const givesTheQuestionBack = [
          "LLM_UNAVAILABLE",
          "NETWORK_ERROR",
          "TIMEOUT",
          "RATE_LIMITED",
          "CAPACITY",
        ].includes(failure.code);

        if (givesTheQuestionBack) {
          setMessages((list) => list.slice(0, -1));
          setDraft(question);
        }
        if (failure.code === "RATE_LIMITED" || failure.code === "CAPACITY") {
          setStatus("blocked");
          return;
        }
      } finally {
        streamFinished();
        controller.current = null;
        setStatus((current) => (current === "blocked" ? current : "idle"));
      }
    },
    [historyFrom, messages, reportId, status],
  );

  /** Lets the countdown re-enable the input when a rate limit expires. */
  const unblock = useCallback(() => {
    setError(null);
    setStatus("idle");
  }, []);

  // A sign-in in progress outranks idle for the input's sake, but never interrupts a stream
  // already running - that answer finishes under the credential it started with.
  const effectiveStatus: ChatStatus =
    signingIn && status === "idle" ? "signing-in" : status;

  return { messages, status: effectiveStatus, error, draft, setDraft, ask, stop, unblock };
}
