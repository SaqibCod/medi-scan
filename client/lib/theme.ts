/**
 * Theme constants, shared by the provider, the toggle and their tests.
 *
 * Order of precedence (LLD 4.3): the visitor's saved choice, then the device setting, then
 * dark. `defaultTheme: "system"` is what expresses "follow the device until they choose" —
 * next-themes only writes to storage inside `setTheme`, so nothing is stored until the toggle
 * is actually pressed, and the toggle only ever writes a concrete theme.
 */

export const THEMES = ["dark", "light"] as const;

export type Theme = (typeof THEMES)[number];

/**
 * The one `localStorage` key in the app.
 *
 * Root CLAUDE.md bans `localStorage` for tokens and makes exactly this exception: a theme is a
 * display preference, not a credential, and it has to outlive the tab or a returning visitor
 * gets a flash of the wrong theme on every visit. Tokens stay in memory and `sessionStorage`.
 */
export const THEME_STORAGE_KEY = "ms-theme";

/** Set on `<html>`. Every colour token is defined against `:root` and `[data-theme="…"]`. */
export const THEME_ATTRIBUTE = "data-theme";

export const DEFAULT_THEME: Theme = "dark";

export function isTheme(value: unknown): value is Theme {
  return value === "dark" || value === "light";
}

/**
 * Reads the theme the pre-hydration script already put on `<html>`.
 *
 * React does not know the theme on its first render — the value lives in `localStorage`, which
 * the server cannot see. The inline script has however already set the attribute before the
 * browser painted, so the DOM is the one place that is right this early. Seeding state from it
 * (the "lazy state initializer" pattern in Next's preventing-flash guide) means the toggle
 * shows the correct icon on the very first frame rather than correcting itself after mount.
 */
export function readAppliedTheme(): Theme {
  if (typeof document === "undefined") return DEFAULT_THEME;
  const applied = document.documentElement.getAttribute(THEME_ATTRIBUTE);
  return isTheme(applied) ? applied : DEFAULT_THEME;
}
