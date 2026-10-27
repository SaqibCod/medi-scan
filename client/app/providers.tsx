"use client";

import { useState, type ReactNode } from "react";
import { QueryClientProvider } from "@tanstack/react-query";
import { CSPProvider } from "@base-ui/react/csp-provider";
import { ThemeProvider } from "next-themes";

import { NoticeProvider } from "@/components/notices/notice-provider";
import { ToastProvider } from "@/components/notices/toaster";
import { createQueryClient } from "@/lib/query-client";
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
  // Created here rather than at module scope: a module-level client is shared across requests
  // on the server, which would leak one visitor's cached reports into another's render.
  const [queryClient] = useState(createQueryClient);

  return (
    // Nonces Base UI's own inline <style> and <script> tags, the scroll-lock ones in
    // particular. Without it the mobile menu would be blocked by the CSP.
    <CSPProvider nonce={nonce}>
      <ThemeProvider
        attribute={THEME_ATTRIBUTE}
        // "system" is what makes it follow the device until the visitor picks. With no device
        // signal at all the page stays dark, because tokens.css defines dark on bare `:root`
        // as well as on `[data-theme="dark"]`.
        defaultTheme="system"
        enableSystem
        enableColorScheme
        storageKey={THEME_STORAGE_KEY}
        themes={[...THEMES]}
        nonce={nonce}
      >
        <QueryClientProvider client={queryClient}>
          <NoticeProvider>
            <ToastProvider>{children}</ToastProvider>
          </NoticeProvider>
        </QueryClientProvider>
      </ThemeProvider>
    </CSPProvider>
  );
}
