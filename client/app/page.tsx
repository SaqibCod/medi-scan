import Link from "next/link";

/**
 * Temporary home page (LLD 9.4).
 *
 * The landing page — hero, upload panel, samples, the how-it-works timeline — is 7b. This
 * stands in with the real headline and nothing that pretends to work: no upload control and no
 * button that does nothing (LLD F8).
 */
export default function HomePage() {
  return (
    <main className="mx-auto w-full max-w-[1360px] px-space-4 py-space-9 md:px-space-8 md:py-space-10">
      <p className="t-eyebrow text-accent-ink">AI lab report reader</p>

      <h1 className="t-display-xl mt-space-5 max-w-[16ch] text-ink">
        Your lab report,
        <br />
        <span className="text-faint">in plain language.</span>
      </h1>

      <p className="t-body-l mt-space-5 max-w-[52ch] text-ink-2">
        Upload a blood test or lab PDF. Medi-Scan reads every value, checks it against the
        reference range printed on your report, and explains what it says.
      </p>

      <p className="t-body mt-space-7 text-ink-2">
        <Link href="/privacy" className="text-accent-ink underline underline-offset-4">
          How your data is handled
        </Link>
      </p>
    </main>
  );
}
