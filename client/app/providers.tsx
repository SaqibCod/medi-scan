"use client";

import type { ReactNode } from "react";
import { ThemeProvider } from "next-themes";

import { THEME_ATTRIBUTE, THEME_STORAGE_KEY, THEMES } from "@/lib/theme";

/**
 * Everything the tree needs on the client.
 *
 * The theme comes from `next-themes` rather than a hand-written script for one specific
 * reason: setting `data-theme` before first paint means emitting an inline `<script>`, and the
 * only way React can do that is `dangerouslySetInnerHTML`, which root CLAUDE.md bans outright.
 * next-themes does that injection inside the library, so no application file ever calls it,
 * and it takes a `nonce` so the script survives the strict CSP (LLD F6, F7).
 *
 * `disableTransitionOnChange` is deliberately NOT set: the design system asks for a 0.3s fade
 * of the ground and the text on a theme change, which ds.css defines and reduced motion turns
 * off.
 */
export function Providers({ children, nonce }: { children: ReactNode; nonce?: string }) {
  return (
    <ThemeProvider
      attribute={THEME_ATTRIBUTE}
      // "system" is what makes it follow the device until the visitor picks. With no device
      // signal at all the page stays on `DEFAULT_THEME`, because tokens.css defines dark on
      // bare `:root` as well as on `[data-theme="dark"]`.
      defaultTheme="system"
      enableSystem
      enableColorScheme
      storageKey={THEME_STORAGE_KEY}
      themes={[...THEMES]}
      nonce={nonce}
    >
      {children}
    </ThemeProvider>
  );
}
