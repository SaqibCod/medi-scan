"use client";

import { useEffect, useState } from "react";

import { Button } from "@/components/ds";
import { InlineNotice } from "@/components/notices/inline-notice";
import { copy } from "@/lib/copy";
import type { ApiError } from "@/lib/api-error";
import type { UploadProblem } from "@/lib/upload-source";

/**
 * Everything that can stop an upload, said in one place (LLD 5.4).
 *
 * Keyed on the error `code` and never on the server's wording, which is written for a log.
 * Where the server knows something the browser does not — the real file size it measured —
 * that detail is offered as supporting text rather than as the message.
 */

export interface UploadErrorNoticeProps {
  /** Something the browser caught before sending. */
  problem?: UploadProblem | null;
  /** What the server said. */
  failure?: ApiError | null;
  /** A drop that carried more than one file. Not an error, so it reads differently. */
  tooManyFiles?: boolean;
  /** "Paste text instead", on the photo notice. */
  onPasteInstead?: () => void;
  /** "Try again", on the failures where trying again is the right move. */
  onRetry?: () => void;
}

/** "17 min 51 s", ticking down. The wait is the whole content of a rate-limit message. */
function formatWait(totalSeconds: number): string {
  const seconds = Math.max(0, totalSeconds);
  const minutes = Math.floor(seconds / 60);
  const rest = seconds % 60;
  return minutes > 0 ? `${minutes} min ${rest} s` : `${rest} s`;
}

function useCountdown(seconds: number | null): number | null {
  const [left, setLeft] = useState(seconds);

  // A new limit resets the clock during render rather than in an effect, so the first frame
  // already shows the new number instead of flashing the old one for a tick.
  const [tracked, setTracked] = useState(seconds);
  if (tracked !== seconds) {
    setTracked(seconds);
    setLeft(seconds);
  }

  useEffect(() => {
    if (left === null || left <= 0) return;
    const timer = setInterval(() => setLeft((value) => (value === null ? null : value - 1)), 1000);
    return () => clearInterval(timer);
  }, [left]);

  return left;
}

interface Content {
  title: string;
  body: string;
  code?: string;
  tone?: "warn" | "info";
  action?: "paste" | "retry";
}

function fromProblem(problem: UploadProblem): Content {
  switch (problem.kind) {
    case "file-too-large":
      return {
        title: "This file is too large.",
        body: `It is ${problem.size} and the limit is 10 MB. Try exporting only the pages with your results.`,
        code: "413 FILE_TOO_LARGE",
      };
    case "photo-not-yet":
      return {
        title: copy.photoNotYet.title,
        body: copy.photoNotYet.body,
        action: "paste",
      };
    case "wrong-type":
      return {
        title: "We can't read this kind of file.",
        body: copy.unsupportedFileTypeBody,
        code: "415 UNSUPPORTED_FILE_TYPE",
      };
    case "text-too-short":
      return {
        title: "That text is too short to read.",
        body: "Paste at least 20 characters, including the test names, values and ranges.",
      };
    case "text-too-long":
      return {
        title: "That text is too long.",
        body: "Paste up to 50,000 characters. Try only the part with your results.",
      };
  }
}

function fromFailure(failure: ApiError, wait: number | null): Content {
  const code = `${failure.status} ${failure.code}`;

  switch (failure.code) {
    case "FILE_TOO_LARGE":
      return {
        title: "This file is too large.",
        body: "The limit is 10 MB. Try exporting only the pages with your results.",
        code,
      };
    case "UNSUPPORTED_FILE_TYPE":
      return { title: "We can't read this kind of file.", body: copy.unsupportedFileTypeBody, code };
    case "FILE_SIGNATURE_MISMATCH":
      return {
        title: "This file isn't really a PDF.",
        body: "Its contents do not match its name. It may have been renamed or damaged. Try saving it again from the original app.",
        code,
      };
    case "RATE_LIMITED":
      return {
        title: "You've reached this hour's upload limit.",
        body:
          wait === null
            ? "You can upload 10 reports an hour. Try again shortly."
            : `You can upload 10 reports an hour. Try again in ${formatWait(wait)}.`,
        code,
      };
    case "BUSY":
      return {
        title: "Lots of reports are being read right now.",
        body: "Your report was not sent. Try again in a minute.",
        code,
        action: "retry",
      };
    case "CAPACITY":
      return {
        title: "The demo has hit today's AI limit.",
        body: "New reports open again at midnight UTC. Your saved reports still open.",
        code,
      };
    case "SAMPLE_NOT_FOUND":
      return {
        title: "That sample isn't available.",
        body: "Pick another sample, or upload your own report.",
        code,
      };
    case "VALIDATION_ERROR":
      return {
        title: "Something in the request wasn't right.",
        body: describeFields(failure),
        code,
      };
    case "NETWORK_ERROR":
    case "TIMEOUT":
      return {
        title: "We couldn't reach the server.",
        body: "Your report was not sent. Try again when the server is back.",
        action: "retry",
      };
    default:
      if (failure.status >= 500) {
        return {
          title: "Something broke on our side.",
          body: failure.requestId
            ? `Try again in a moment. Request ID ${failure.requestId}.`
            : "Try again in a moment.",
          code,
          action: "retry",
        };
      }
      return {
        title: "That didn't go through.",
        body: "Your report was not sent. Try again in a moment.",
        code,
        action: "retry",
      };
  }
}

/** Field names from `errors`, in plain words rather than as a JSON path. */
function describeFields(failure: ApiError): string {
  const names: Record<string, string> = {
    file: "the file",
    text: "the pasted text",
    sampleId: "the sample",
    consent: "the consent box",
  };

  const listed = failure.errors.map((e) => names[e.field] ?? e.field);
  if (listed.length === 0) return "Check what you chose and try again.";

  const joined =
    listed.length === 1
      ? listed[0]
      : `${listed.slice(0, -1).join(", ")} and ${listed[listed.length - 1]}`;

  return `The server could not accept ${joined}. Try again, or try a different file.`;
}

export function UploadErrorNotice({
  problem,
  failure,
  tooManyFiles,
  onPasteInstead,
  onRetry,
}: UploadErrorNoticeProps) {
  // Only meaningful for RATE_LIMITED, but the hook has to run on every render regardless.
  const wait = useCountdown(failure?.code === "RATE_LIMITED" ? failure.retryAfterSec : null);

  if (!problem && !failure && !tooManyFiles) return null;

  // The server's answer wins: it is about the attempt that just happened, and the browser's
  // own check already passed for this source.
  const content: Content = failure
    ? fromFailure(failure, wait)
    : problem
      ? fromProblem(problem)
      : {
          title: "Only one file at a time.",
          body: "The first file you dropped is the one we'll read.",
          tone: "info",
        };

  const action =
    content.action === "paste" && onPasteInstead ? (
      <Button variant="outline" size="sm" onClick={onPasteInstead}>
        {copy.photoNotYet.action}
      </Button>
    ) : content.action === "retry" && onRetry ? (
      <Button variant="outline" size="sm" onClick={onRetry}>
        Try again
      </Button>
    ) : undefined;

  return (
    <InlineNotice
      tone={content.tone ?? "warn"}
      title={content.title}
      code={content.code}
      action={action}
      // An upload that was just refused has to interrupt: focus is moving here and the next
      // natural pause is after they have pressed the button again.
      assertive={content.tone !== "info"}
    >
      <p>{content.body}</p>
    </InlineNotice>
  );
}
