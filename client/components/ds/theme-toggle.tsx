"use client";

import type { CSSProperties } from "react";

import { Icon } from "./icon";
import type { Theme } from "@/lib/theme";

export interface ThemeToggleProps {
  /**
   * Accepted for API compatibility with the design system, and so a consumer can be explicit.
   * It deliberately does not decide what is drawn — see below.
   */
  theme?: Theme;
  onToggle: () => void;
  style?: CSSProperties;
}

/**
 * Sun/moon button that flips the theme.
 *
 * Both icons and both labels are always rendered, and CSS keyed on `[data-theme]` shows one.
 * That is not a flourish — it is the only way to get all three of these at once:
 *
 *   - **No flash.** The pre-paint script sets `data-theme` before anything is drawn, so the
 *     correct icon is the one that appears first. Deciding in React means deciding after
 *     hydration, which is after paint.
 *   - **No hydration mismatch.** The server cannot know the theme. If the markup depended on
 *     it, the server would render a sun and a light-mode visitor's browser a moon, and React
 *     would throw away and re-render the tree on every page load.
 *   - **A correct accessible name.** `display: none` removes the hidden label from the
 *     accessibility tree, so a screen reader reads exactly one — and it is the right one.
 *
 * The label says what a press will *do*, not what the theme currently is: "Switch to light
 * mode" is actionable, "Dark mode" is a riddle.
 */
export function ThemeToggle({ onToggle, style }: ThemeToggleProps) {
  return (
    <button type="button" className="ms-iconbtn ms-themetoggle" onClick={onToggle} style={style}>
      <span className="ms-themetoggle__when-dark">
        <Icon name="sun" size={18} />
        <span className="sr-only">Switch to light mode</span>
      </span>

      <span className="ms-themetoggle__when-light">
        <Icon name="moon" size={18} />
        <span className="sr-only">Switch to dark mode</span>
      </span>
    </button>
  );
}
