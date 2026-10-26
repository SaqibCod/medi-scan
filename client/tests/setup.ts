import "@testing-library/jest-dom/vitest";

import { cleanup } from "@testing-library/react";
import { afterEach, beforeEach } from "vitest";

afterEach(() => {
  cleanup();
});

beforeEach(() => {
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
if (!window.matchMedia) {
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
