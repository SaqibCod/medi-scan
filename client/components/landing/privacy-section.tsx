import Link from "next/link";

import { Eyebrow, Icon } from "@/components/ds";

/**
 * What the app does with a report, in four statements.
 *
 * Every one is a fact about the running build rather than an intention, and each is checked
 * against SECURITY.md. The marquee above this section says the same things in two words; the
 * aria-hidden marquee is decoration, and this is where those claims are actually readable.
 */
const CARDS = [
  {
    title: "Guest by default",
    body: "No account needed. Guest reports and their session are deleted automatically after 24 hours.",
  },
  {
    title: "Sign in to keep history",
    body: "With Google sign-in, reports stay for 30 days and you can follow a marker over time on the Trends page.",
  },
  {
    title: "Chat is never stored",
    body: "Your questions and answers live only in your browser tab. The server keeps none of them.",
  },
  {
    title: "Information, not diagnosis",
    body: "Medi-Scan explains what your report says. It won't diagnose or suggest treatment. Talk to your doctor for that.",
  },
];

export function PrivacySection() {
  return (
    <section id="privacy" aria-labelledby="privacy-heading" className="scroll-mt-[96px] py-space-9">
      <div className="grid gap-space-8 md:grid-cols-[minmax(0,1fr)_minmax(0,1.6fr)] md:gap-space-9">
        <div>
          <Eyebrow>Privacy</Eyebrow>
          <h2 id="privacy-heading" className="t-display-m mt-space-4 max-w-[12ch] text-ink">
            Your data,
            <br />
            <span className="text-faint">on a short leash.</span>
          </h2>
          <p className="t-body-s mt-space-6 text-ink-2">
            Private by default, and every value flagged.{" "}
            <Link href="/privacy" className="text-accent-ink underline underline-offset-4">
              How your data is handled
            </Link>
          </p>
        </div>

        <ul className="flex flex-col">
          {CARDS.map((card) => (
            <li
              key={card.title}
              className="grid gap-space-3 border-t border-line py-space-5 md:grid-cols-[minmax(0,1fr)_minmax(0,1.4fr)] md:gap-space-6"
            >
              <h3 className="t-body flex items-start gap-space-3 text-ink">
                <span className="mt-[2px] shrink-0 text-accent-ink">
                  <Icon name="plus" size={16} />
                </span>
                {card.title}
              </h3>
              <p className="t-body-s text-ink-2">{card.body}</p>
            </li>
          ))}
        </ul>
      </div>
    </section>
  );
}
