"use client";

import { Button, Eyebrow, Helix, Icon } from "@/components/ds";

const SECTIONS = [
  { id: "upload", label: "Intro" },
  { id: "how", label: "How it works" },
  { id: "samples", label: "Samples" },
  { id: "results", label: "Results" },
  { id: "privacy", label: "Privacy" },
];

/** Scrolls to a section and puts the keyboard there too, rather than only the viewport. */
function focusSection(id: string) {
  const target = document.getElementById(id);
  if (!target) return;

  target.scrollIntoView({ behavior: "smooth", block: "start" });

  // Without this the page moves but Tab carries on from the button that was pressed, which
  // on a long landing page means the next Tab jumps back up to the header.
  target.setAttribute("tabindex", "-1");
  target.focus({ preventScroll: true });
}

export function Hero() {
  return (
    // `overflow-x-clip`, not `hidden`: the helix's drift animation makes its inner box wider
    // than the width it is given, which would otherwise give the whole page a horizontal
    // scrollbar on phones. `clip` does not create a scroll container, so the sticky section
    // index beside it still sticks — `hidden` would silently break it.
    <section className="relative overflow-x-clip">
      {/* Decorative, once per page, and client-only (F9). */}
      <div
        className="pointer-events-none absolute -top-24 right-0 hidden opacity-90 lg:block"
        aria-hidden="true"
      >
        <Helix width={560} height={860} />
      </div>
      <div
        className="pointer-events-none absolute -top-10 right-0 opacity-75 lg:hidden"
        aria-hidden="true"
      >
        <Helix width={300} height={540} />
      </div>

      <div className="relative flex gap-space-8 py-space-9 md:py-space-10">
        <nav aria-label="Sections of this page" className="hidden shrink-0 lg:block">
          <ul className="sticky top-[120px] flex flex-col">
            {SECTIONS.map((section) => (
              <li key={section.id}>
                <a
                  href={`#${section.id}`}
                  className="t-meta block border-l border-line py-space-2 pl-space-3 text-ink-3 hover:border-accent hover:text-ink"
                >
                  {section.label}
                </a>
              </li>
            ))}
          </ul>
        </nav>

        <div className="min-w-0 flex-1">
          <Eyebrow>AI lab report reader</Eyebrow>

          <h1 className="t-display-xl mt-space-5 max-w-[16ch] text-ink">
            Your lab report,
            <br />
            <span className="text-faint">in plain language.</span>
          </h1>

          <p className="t-body-l mt-space-5 max-w-[48ch] text-ink-2">
            Upload a blood test or lab PDF. Medi-Scan reads every value, checks it against the
            reference range printed on your report, and explains what it means.
          </p>

          <div className="mt-space-7 flex flex-wrap items-center gap-space-3">
            <Button icon="upload" onClick={() => focusSection("upload")}>
              Upload a report
            </Button>
            <Button variant="outline" onClick={() => focusSection("samples")}>
              Try a sample report
            </Button>
          </div>
        </div>

        <p className="t-meta absolute bottom-space-7 right-0 hidden items-center gap-space-2 rounded-full border border-line px-space-4 py-space-2 text-ink-3 lg:flex">
          <Icon name="arrow-down" size={14} />
          Scroll for more
        </p>
      </div>
    </section>
  );
}
