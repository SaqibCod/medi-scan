"use client";

import type { CSSProperties } from "react";

import { IconButton } from "./icon-button";
import type { Theme } from "@/lib/theme";

export interface ThemeToggleProps {
  theme: Theme;
  onToggle: () => void;
  style?: CSSProperties;
}

/**
 * Sun/moon button that flips the theme. The consumer owns the state and sets `data-theme`.
 *
 * The label says what a press will *do*, not what the current theme is — "Switch to light
 * mode" is actionable, "Dark mode" is a riddle.
 */
export function ThemeToggle({ theme, onToggle, style }: ThemeToggleProps) {
  const dark = theme === "dark";

  return (
    <IconButton
      icon={dark ? "sun" : "moon"}
      label={dark ? "Switch to light mode" : "Switch to dark mode"}
      onClick={onToggle}
      style={style}
    />
  );
}
