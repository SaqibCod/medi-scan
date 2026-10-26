"use client";

import { Button } from "@/components/ds";
import { useNotices } from "@/components/notices/notice-provider";
import { RequestIdBlock } from "./request-id-block";
import { RetryCounter } from "./retry-counter";
import { SystemScreen } from "./system-screen";

/**
 * The six system screens (LLD 9.3), copied from the mockups.
 *
 * Each is exported on its own because most of them are not routes: a report that has expired
 * and a guest session that has ended are states a page enters, not URLs anyone visits.
 *
 * Sign-in buttons are absent rather than disabled until 7c (F8). Where a mockup shows one,
 * the remaining buttons simply close up.
 */

/** `app/not-found.tsx`. Mockups `--01` and `--08`. */
export function NotFoundScreen() {
  return (
    <SystemScreen
      eyebrow="Page not found"
      headline="This page doesn't exist."
      headlineFaint="Let's get you back."
      numeral="404"
      actions={
        <>
          <Button href="/" icon="arrow-right">
            Back to home
          </Button>
          <Button href="/reports" variant="outline">
            My reports
          </Button>
        </>
      }
    >
      The link may be mistyped, or the page has moved. Nothing about your reports has changed.
    </SystemScreen>
  );
}

/** Used by 7b on `REPORT_NOT_FOUND`. Mockups `--02` and `--09`. */
export function ReportNotAvailableScreen() {
  return (
    <SystemScreen
      eyebrow="Report not available"
      headline="We can't find this report."
      headlineFaint="It may have expired."
      numeral="404"
      actions={
        <>
          <Button href="/" icon="upload">
            Analyze a report
          </Button>
          <Button href="/reports" variant="outline">
            My reports
          </Button>
        </>
      }
      notes={[
        "Guest reports are deleted 24 hours after the session started. Saved reports last 30 days.",
        "Opened it in a new tab? Make sure you are signed in there with the same Google account.",
      ]}
    >
      It was deleted, or it reached its time limit, or it belongs to a different account or
      browser tab.
    </SystemScreen>
  );
}

/** Shown on the `guest-session-ended` event. Mockup `--03`. */
export function GuestSessionEndedScreen() {
  return (
    <SystemScreen
      eyebrow="Guest session ended"
      headline="Your guest session has ended."
      headlineFaint="Its reports are gone."
      numeral="401"
      actions={
        // "Analyze a report" goes home, which is where analysing will live from 7b. It starts
        // nothing by itself — a new guest session is created by the first action that needs
        // one, never by arriving on a page.
        <Button href="/" icon="upload">
          Analyze a report
        </Button>
      }
    >
      Guest sessions last 24 hours, and their reports are deleted with them. Start again now, or
      sign in so your next reports are kept for 30 days.
    </SystemScreen>
  );
}

/** Used by 7d on `FORBIDDEN`. Mockup `--04`. */
export function ForbiddenScreen() {
  return (
    <SystemScreen
      eyebrow="Admins only"
      tone="warn"
      headline="This page is for admins only."
      headlineFaint="Your account can't open it."
      numeral="403"
      actions={
        <Button href="/" icon="arrow-right">
          Back to home
        </Button>
      }
    >
      You&rsquo;re signed in, but your account doesn&rsquo;t have access to this page. If you
      think that&rsquo;s wrong, the role is set on the server.
    </SystemScreen>
  );
}

/** `app/error.tsx`, and the fallback for an unhandled API failure. Mockup `--05`. */
export function ServerErrorScreen({
  requestId,
  onRetry,
}: {
  /** Omitted when a render failed client-side, where there is no request to quote. */
  requestId?: string | null;
  onRetry?: () => void;
}) {
  return (
    <SystemScreen
      eyebrow="Something went wrong"
      tone="warn"
      headline="Something broke on our side."
      headlineFaint="It's not you."
      numeral="500"
      aside={requestId ? <RequestIdBlock requestId={requestId} /> : null}
      actions={
        <>
          {onRetry && (
            <Button onClick={onRetry} icon="arrow-right">
              Try again
            </Button>
          )}
          <Button href="/" variant={onRetry ? "outline" : "solid"}>
            Back to home
          </Button>
        </>
      }
    >
      Please try again in a moment.{" "}
      {requestId
        ? "If it keeps happening, quote this request ID when you report it."
        : "If it keeps happening, reload the page."}
    </SystemScreen>
  );
}

/** Driven by the backend checker reaching `DOWN`. Mockup `--06`. */
export function ServerNotRespondingScreen() {
  return (
    <SystemScreen
      eyebrow="Can't reach the server"
      tone="warn"
      headline="The server isn't responding."
      headlineFaint="We'll keep trying."
      numeral="503"
      aside={<RetryCounter />}
      actions={
        <>
          <RetryNowButton />
          <Button href="/" variant="outline">
            Back to home
          </Button>
        </>
      }
    >
      Medi-Scan runs on a free server that sleeps when nobody is using it and can be slow to
      wake. Your reports are safe. This page reconnects by itself.
    </SystemScreen>
  );
}

/**
 * Takes the retry from the notices context rather than a prop, so the screen can be dropped
 * on any page without threading a callback through everything that might show it.
 */
function RetryNowButton() {
  const { retryBackendNow } = useNotices();

  return (
    <Button onClick={retryBackendNow} icon="arrow-right">
      Retry now
    </Button>
  );
}
