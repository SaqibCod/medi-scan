import type { Metadata } from "next";

import { Card, Eyebrow, Icon, type IconName } from "@/components/ds";
import { ContentSection, SectionIndex, type Section } from "@/components/content/section-index";
import { PageContainer } from "@/components/shell/page-container";
import { REPO_URL } from "@/lib/env";

export const metadata: Metadata = {
  title: "Privacy",
  description:
    "What Medi-Scan collects, what it never keeps, how long it keeps anything, and who can see it.",
};

/**
 * Every fact on this page is checked against SECURITY.md, docs/plan.md and
 * docs/api-contract.md. Nothing here describes behaviour the backend does not have.
 *
 * Two places where the mockup and the repo disagreed, resolved the way docs/ui/README.md
 * says to resolve them — the docs win:
 *
 *  - The mockup heads a block "Sign-in cookies". There are no cookies anywhere in this app,
 *    by design; it says "Sign-in tokens" (LLD 9.4).
 *
 * The sign-in retention row quotes both limits on purpose. A refresh token is valid 7 days
 * from issue, and a family cannot outlive 30 days from the original sign-in. Quoting either
 * one alone would be wrong for half the cases.
 */

const SECTIONS: Section[] = [
  { id: "collect", title: "What we collect" },
  { id: "never", title: "What we don't keep" },
  { id: "processed", title: "How your report is processed" },
  { id: "retention", title: "How long we keep it" },
  { id: "who", title: "Who can see it" },
  { id: "choices", title: "Your choices" },
  { id: "medical", title: "Medical note and contact" },
];

const SUMMARY: { icon: IconName; title: string; body: string }[] = [
  {
    icon: "clock",
    title: "Guest by default",
    body: "No account needed. Guest reports are deleted 24 hours after your session started.",
  },
  {
    icon: "file",
    title: "Saved reports last 30 days",
    body: "Sign in and each report is kept for 30 days, then deleted automatically.",
  },
  {
    icon: "sources",
    title: "Chat is never stored",
    body: "Your questions and the answers stay in your browser tab only.",
  },
  {
    icon: "user",
    title: "Names masked before AI",
    body: "Names and personal details are hidden before any text reaches the AI service.",
  },
  {
    icon: "trash",
    title: "Delete any time",
    body: "Delete a single report, or your whole account and everything in it.",
  },
  {
    icon: "chart",
    title: "Admins see totals only",
    body: "The admin view counts reports. It never shows contents or names.",
  },
];

const PIPELINE: { step: string; title: string; body: string }[] = [
  {
    step: "01",
    title: "Read",
    body: "We pull the text out of your file, or take the text you pasted. The file itself is a temporary file, deleted as soon as the text is out of it.",
  },
  {
    step: "02",
    title: "Mask",
    body: "Names and personal details are hidden before anything leaves our server. Only masked text is sent onward or stored.",
  },
  {
    step: "03",
    title: "Extract and explain",
    body: "The masked text is sent to Google's Gemini API to find the lab values and write a plain summary. We keep the values and the summary for you.",
  },
  {
    step: "04",
    title: "Chat",
    body: "When you ask a question, it is sent with the relevant parts of your report to Google's Gemini API. The answer streams back to your tab and is not saved.",
  },
];

const RETENTION: { what: string; how: string }[] = [
  { what: "Guest reports and their results", how: "24 hours after the guest session started" },
  { what: "Saved reports and their results", how: "30 days after upload, or until you delete them" },
  // Two limits, and quoting only one of them would mislead: a refresh token is valid 7 days
  // from issue, and a family cannot outlive 30 days from the original sign-in
  // (plan.md:259, dataflow.md:229). So an idle session dies at 7 days and an actively used
  // one still ends at 30.
  {
    what: "Sign-in sessions",
    how: "Up to 30 days from signing in, 7 days without use, or until you sign out",
  },
  { what: "Chat messages", how: "Never stored on our server" },
  { what: "Accounts with no sign-in", how: "Deleted after 180 days" },
  { what: "Usage counts and error logs", how: "90 days" },
];

const CHOICES: { icon: IconName; title: string; body: string }[] = [
  {
    icon: "trash",
    title: "Delete a report",
    body: "Any time, from the report or My reports. It's gone right away.",
  },
  {
    icon: "user",
    title: "Delete your account",
    body: "Removes every report, result and sign-in linked to it. There is no recovery window.",
  },
  {
    icon: "clock",
    title: "Stay a guest",
    body: "No account needed. Everything is gone after 24 hours.",
  },
];

export default function PrivacyPage() {
  return (
    <PageContainer className="py-space-9 md:py-space-10">
      <Eyebrow>Privacy</Eyebrow>

      <h1 className="t-display-m mt-space-4 text-ink">
        Your data, on a short leash.
        <br />
        <span className="text-faint">The plain version first.</span>
      </h1>

      <p className="t-meta mt-space-4 text-ink-3">Last updated 26 October 2026</p>

      <ul className="mt-space-7 grid gap-space-4 sm:grid-cols-2 lg:grid-cols-4">
        {SUMMARY.map((item) => (
          <li key={item.title}>
            <Card>
              <span className="mb-space-4 inline-flex size-[38px] items-center justify-center rounded-sm bg-accent-soft text-accent-ink">
                <Icon name={item.icon} size={18} />
              </span>
              <p className="t-body text-ink">{item.title}</p>
              <p className="t-body-s mt-space-2 text-ink-2">{item.body}</p>
            </Card>
          </li>
        ))}
      </ul>

      <div className="mt-space-10 grid gap-space-7 lg:grid-cols-[220px_minmax(0,1fr)]">
        <SectionIndex sections={SECTIONS} />

        <div className="flex min-w-0 flex-col gap-space-8">
          <ContentSection id="collect" title="What we collect">
            <p className="t-body text-ink-2">
              <b className="font-medium text-ink">The reports you send.</b> When you upload a
              file, paste text or pick a sample, Medi-Scan reads the text, finds the lab values
              and writes a short summary. We keep that text with names and personal details
              masked, the values, and the summary, so you can open the report again.
            </p>
            <p className="t-body text-ink-2">
              <b className="font-medium text-ink">If you sign in with Google:</b> an ID that
              Google gives us for your account, and your display name. That is all — not your
              email address, not your profile photo, and not your Google password, which we
              never see.
            </p>
            <p className="t-body text-ink-2">
              <b className="font-medium text-ink">Basic technical data.</b> Your IP address is
              used to apply fair-use limits, like ten uploads an hour, and is not written to the
              database. Each request also gets a random request ID, which goes in our logs so an
              error can be traced back to what broke.
            </p>
          </ContentSection>

          <ContentSection id="never" title="What we don't keep">
            <p className="t-body text-ink-2">
              <b className="font-medium text-ink">Your chat messages.</b> Questions you ask
              about a report, and the answers, stay in your browser tab. Nothing from the chat
              is saved on our server, for guests or for signed-in users.
            </p>
            <p className="t-body text-ink-2">
              {/* The mockup heads this "Sign-in cookies". There are no cookies in this app. */}
              <b className="font-medium text-ink">Sign-in tokens.</b> Medi-Scan uses no cookies
              at all. Sign-in tokens live only in the browser tab you used and end when you sign
              out or close it. We don&rsquo;t sell your data or use it for advertising.
            </p>
          </ContentSection>

          <ContentSection id="processed" title="How your report is processed">
            <ol className="flex flex-col">
              {PIPELINE.map((stage) => (
                <li
                  key={stage.step}
                  className="flex gap-space-4 border-t border-line py-space-4 last:border-b"
                >
                  <span className="t-meta shrink-0 text-ink-3" aria-hidden="true">
                    {stage.step}
                  </span>
                  <span className="min-w-0">
                    <span className="t-body block text-ink">{stage.title}</span>
                    <span className="t-body-s mt-space-1 block text-ink-2">{stage.body}</span>
                  </span>
                </li>
              ))}
            </ol>

            <p className="t-body-s text-ink-2">
              Medi-Scan runs on a free tier of that AI service, and free-tier terms generally
              allow the provider to use what is sent to improve their models. Masking happens
              first, but masking is best-effort and can miss things — which is why this is a
              demo built for made-up reports, and why the upload form says so.
            </p>
          </ContentSection>

          <ContentSection id="retention" title="How long we keep it">
            {/*
              A scrollable box has to be reachable by keyboard, or someone who cannot use a
              pointer simply cannot see the right-hand column. `tabIndex={0}` makes it
              focusable and arrow-scrollable; the role and label stop it being an unexplained
              stop in the tab order. Caught by axe at phone width.
            */}
            <div
              className="overflow-x-auto"
              tabIndex={0}
              role="region"
              aria-label="How long we keep it"
            >
              <table className="w-full min-w-[420px] border-collapse text-left">
                <thead>
                  <tr>
                    <th scope="col" className="t-meta border-b border-line pb-space-3 text-ink-3">
                      WHAT
                    </th>
                    <th scope="col" className="t-meta border-b border-line pb-space-3 text-ink-3">
                      HOW LONG
                    </th>
                  </tr>
                </thead>
                <tbody>
                  {RETENTION.map((row) => (
                    <tr key={row.what}>
                      <th
                        scope="row"
                        className="t-body-s border-b border-line py-space-4 pr-space-4 font-normal text-ink"
                      >
                        {row.what}
                      </th>
                      <td className="t-body-s border-b border-line py-space-4 text-ink-2">
                        {row.how}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </ContentSection>

          <ContentSection id="who" title="Who can see it">
            <p className="t-body text-ink-2">
              Only you. A guest report belongs to the browser session that made it, and a saved
              report belongs to your account. Nobody else can open it, and the app never even
              confirms whether a report exists for someone else — asking for one that is not
              yours returns the same &ldquo;not found&rdquo; as one that never existed.
            </p>
            <p className="t-body text-ink-2">
              The admin dashboard shows totals only, like how many reports were made today. It
              has no report contents, no report IDs and no names.
            </p>
          </ContentSection>

          <ContentSection id="choices" title="Your choices">
            <ul className="grid gap-space-4 sm:grid-cols-3">
              {CHOICES.map((choice) => (
                <li key={choice.title}>
                  <Card>
                    <span className="mb-space-3 inline-flex text-accent-ink">
                      <Icon name={choice.icon} size={18} />
                    </span>
                    <p className="t-body-s text-ink">{choice.title}</p>
                    <p className="t-caption mt-space-2 text-ink-2">{choice.body}</p>
                  </Card>
                </li>
              ))}
            </ul>
          </ContentSection>

          <ContentSection id="medical" title="Medical note and contact">
            <p className="t-body text-ink-2">
              Medi-Scan explains what your report says. It is not a medical device and does not
              give medical advice, a diagnosis or treatment. Always talk to a doctor about your
              results.
            </p>
            <p className="t-body text-ink-2">
              Questions about this page or your data:{" "}
              {REPO_URL ? (
                <a
                  href={`${REPO_URL}/issues`}
                  rel="noopener noreferrer"
                  className="text-accent-ink underline underline-offset-4"
                >
                  open an issue on GitHub
                </a>
              ) : (
                "open an issue on the project's GitHub repository"
              )}
              . Medi-Scan is a personal portfolio project, run by Saqib.
            </p>
          </ContentSection>
        </div>
      </div>
    </PageContainer>
  );
}
