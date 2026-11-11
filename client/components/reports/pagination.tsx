"use client";

import { Icon } from "@/components/ds";

/**
 * Numbered pages with chevrons either side (mockup `my-reports-page--01`).
 *
 * Numbered rather than "load more" because the page number is in the URL: back and forward
 * work, and a page can be linked. Absent entirely when everything fits on one page — one
 * disabled chevron on each side of a lone "1" is furniture, not navigation.
 */
export function Pagination({
  page,
  totalPages,
  onChange,
}: {
  /** Zero-based, as the contract numbers them. Displayed one-based. */
  page: number;
  totalPages: number;
  onChange: (page: number) => void;
}) {
  if (totalPages <= 1) return null;

  const pages = Array.from({ length: totalPages }, (_, index) => index);

  return (
    <nav className="ms-pager" aria-label="Pages">
      <button
        type="button"
        className="ms-pager__step"
        onClick={() => onChange(page - 1)}
        disabled={page === 0}
        aria-label="Previous page"
      >
        <Icon name="chevron-left" size={16} />
      </button>

      <ul className="ms-pager__list">
        {pages.map((candidate) => (
          <li key={candidate}>
            <button
              type="button"
              className="ms-pager__page"
              // The current page is a state, not a style: screen readers get it from here,
              // and the CSS hangs off the same attribute rather than a second class.
              aria-current={candidate === page ? "page" : undefined}
              onClick={() => onChange(candidate)}
            >
              {candidate + 1}
            </button>
          </li>
        ))}
      </ul>

      <button
        type="button"
        className="ms-pager__step"
        onClick={() => onChange(page + 1)}
        disabled={page >= totalPages - 1}
        aria-label="Next page"
      >
        <Icon name="chevron-right" size={16} />
      </button>
    </nav>
  );
}
