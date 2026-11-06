"use client";

import { useCallback, useState } from "react";
import { useTheme } from "next-themes";

import { isTheme, readAppliedTheme, type Theme } from "./theme";

/**
 * The current theme and a function that flips it.
 *
 * `useTheme().resolvedTheme` is `undefined` until next-themes has mounted, which is after the
 * first paint. Rendering a sun for a visitor who is in light mode and then swapping it to a
 * moon is exactly the flash the whole no-flash setup exists to avoid — so until it resolves,
 * the value comes from the `data-theme` attribute, which the pre-hydration script has already
 * set correctly by the time any of this runs. This is the "lazy state initializer reading the
 * same source as the inline script" pattern from Next's preventing-flash guide.
 *
 * The seed is state rather than a plain read so it is captured once, on the first client
 * render, instead of re-reading a DOM that next-themes is in the middle of updating.
 *
 * Consumers render with `suppressHydrationWarning`: the server cannot know the theme, so the
 * first client value legitimately differs from the server's.
 */
export function useThemeChoice(): { theme: Theme; toggle: () => void } {
  const { resolvedTheme, setTheme } = useTheme();

  const [seeded] = useState(readAppliedTheme);

  // Once next-themes has resolved it is the source of truth — it is what notices the device
  // switching to light while the tab is open.
  const theme = isTheme(resolvedTheme) ? resolvedTheme : seeded;

  const toggle = useCallback(() => {
    setTheme(theme === "dark" ? "light" : "dark");
  }, [theme, setTheme]);

  return { theme, toggle };
}
