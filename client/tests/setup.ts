import "@testing-library/jest-dom/vitest";

import { cleanup } from "@testing-library/react";
import { afterEach, beforeEach } from "vitest";

/**
 * A few specs run under the `node` environment rather than jsdom — proxy.ts, for one, which
 * is server code and whose `NextRequest` wants real Node globals. This file is shared by all
 * of them, so everything DOM-shaped below is guarded.
 */
const hasDom = typeof window !== "undefined";

afterEach(() => {
  if (hasDom) cleanup();
});

beforeEach(() => {
  if (!hasDom) return;
  // Storage is global in jsdom and would otherwise leak between tests — a theme written by one
  // test deciding what the next one renders is the kind of order-dependence that only shows up
  // in CI. `ms-theme` in particular is asserted on in both directions.
  window.localStorage.clear();
  window.sessionStorage.clear();
  document.documentElement.removeAttribute("data-theme");
});

/**
 * jsdom has no `matchMedia`. next-themes calls it on mount to read `prefers-color-scheme`, and
 * several components check `prefers-reduced-motion`, so both need an answer rather than a
 * crash. Tests that care about the result override this with their own implementation.
 */
if (hasDom && !window.matchMedia) {
  window.matchMedia = ((query: string) => ({
    matches: false,
    media: query,
    onchange: null,
    addListener: () => {},
    removeListener: () => {},
    addEventListener: () => {},
    removeEventListener: () => {},
    dispatchEvent: () => false,
  })) as unknown as typeof window.matchMedia;
}
