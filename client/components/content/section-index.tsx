import type { ReactNode } from "react";

export interface Section {
  id: string;
  title: string;
}

/**
 * The "On this page" index (mockups `content-pages--02` and `--04`).
 *
 * Desktop: a sticky column beside the text. Phone: a native `<details>`, which collapses it
 * without a line of JavaScript and is keyboard- and screen-reader-correct for free.
 *
 * No scroll-spy. The mockup shows the first entry highlighted, which implies one, but a
 * highlight that tracks the scroll position is a lot of machinery for a seven-item list — and
 * it tends to fight anchor navigation. Noted for the visual review.
 */
export function SectionIndex({ sections }: { sections: Section[] }) {
  return (
    <>
      <details className="mb-space-6 rounded-lg border border-line px-space-5 py-space-4 lg:hidden">
        <summary className="t-body-s cursor-pointer text-ink">On this page</summary>
        <List sections={sections} className="mt-space-4" />
      </details>

      <nav aria-label="On this page" className="sticky top-[96px] hidden lg:block">
        <p className="t-meta mb-space-4 text-ink-3">ON THIS PAGE</p>
        <List sections={sections} />
      </nav>
    </>
  );
}

function List({ sections, className }: { sections: Section[]; className?: string }) {
  return (
    <ul className={className}>
      {sections.map((section) => (
        <li key={section.id}>
          <a
            href={`#${section.id}`}
            className="t-body-s block border-l border-line py-space-2 pl-space-4 text-ink-2 hover:border-accent hover:text-ink"
          >
            {section.title}
          </a>
        </li>
      ))}
    </ul>
  );
}

/** A section of the page, with the id its index entry points at. */
export function ContentSection({
  id,
  title,
  children,
}: {
  id: string;
  title: string;
  children: ReactNode;
}) {
  return (
    // `scroll-mt` keeps the sticky header off the heading when an anchor is followed.
    <section id={id} className="scroll-mt-[96px] pt-space-8 first:pt-0">
      <h2 className="t-title text-ink">{title}</h2>
      <div className="mt-space-5 flex flex-col gap-space-4">{children}</div>
    </section>
  );
}
