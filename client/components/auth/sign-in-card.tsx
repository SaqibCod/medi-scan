"use client";

import { GoogleButton } from "@/components/auth/google-button";
import { Button, Eyebrow, Helix, Icon } from "@/components/ds";
import { ACCOUNT_COPY } from "@/lib/copy";
import { CAN_SIGN_IN } from "@/lib/env";

/**
 * "Sign in to see your account." (`account-page--03`, `trends-page--04`)
 *
 * One card, two pages. What differs between them is only the headline and the sentence under
 * it — the benefits, the button and the way out are the same offer either way, and writing it
 * twice is how two pages end up promising different things.
 *
 * "Continue as guest" is not a decline button. Guests are a first-class way to use this app;
 * the card exists to explain what signing in adds, not to stand between a visitor and the
 * thing they came for.
 */
export function SignInCard({
  headline,
  headlineFaint,
  body,
  benefits,
  eyebrow,
}: {
  headline: string;
  headlineFaint?: string;
  /** A paragraph instead of the benefit list, as Trends uses. */
  body?: string;
  benefits?: readonly string[];
  eyebrow?: string;
}) {
  return (
    <section className="ms-signincard">
      <div className="ms-signincard__body">
        {eyebrow && <Eyebrow>{eyebrow}</Eyebrow>}

        <h1 className="t-display-m mt-space-4 text-ink">
          {headline}
          {headlineFaint && (
            <>
              <br />
              <span className="text-faint">{headlineFaint}</span>
            </>
          )}
        </h1>

        {body && <p className="t-body mt-space-5 max-w-[48ch] text-ink-2">{body}</p>}

        {benefits && (
          <ul className="mt-space-6 flex flex-col">
            {benefits.map((benefit) => (
              <li
                key={benefit}
                className="flex items-center gap-space-4 border-b border-line py-space-4"
              >
                <span className="shrink-0 text-accent" aria-hidden="true">
                  <Icon name="plus" size={18} />
                </span>
                <span className="t-body-s text-ink-2">{benefit}</span>
              </li>
            ))}
          </ul>
        )}

        <div className="mt-space-6 flex flex-wrap items-center gap-space-3">
          {/* Absent rather than broken when sign-in is not configured. The page then offers
              only the way onward, which is the honest set of choices. */}
          {CAN_SIGN_IN && <GoogleButton width={240} />}
          <Button href="/" variant="outline">
            {ACCOUNT_COPY.signedOut.guest}
          </Button>
        </div>

        <p className="t-caption mt-space-4 text-ink-3">{ACCOUNT_COPY.signedOut.note}</p>
      </div>

      <div className="ms-signincard__art" aria-hidden="true">
        <Helix />
      </div>
    </section>
  );
}
