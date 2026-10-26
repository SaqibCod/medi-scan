"use client";

import Link from "next/link";

import { StatusPill, ThemeToggle } from "@/components/ds";
import { useNotices } from "@/components/notices/notice-provider";
import { pillStatus } from "@/lib/backend-status";
import { useThemeChoice } from "@/lib/use-theme-choice";
import { MobileMenu } from "./mobile-menu";
import { NAV_LINKS } from "./nav";
import { PageContainer } from "./page-container";
import { Wordmark } from "./wordmark";

/**
 * The top bar.
 *
 * Desktop (900px and up): wordmark, nav, spacer, status pill, sign-in slot, theme toggle.
 * Phone: pinned, with the `bar` fill and its blur — wordmark, spacer, short pill, toggle,
 * menu button (design system, "Components").
 */
export function Header() {
  const { backend } = useNotices();
  const { theme, toggle } = useThemeChoice();

  const status = pillStatus(backend.state);

  return (
    <header className="sticky top-0 z-30 border-b border-line bg-bar backdrop-blur-(--blur-bar)">
      <PageContainer>
        <div className="flex h-[72px] items-center gap-space-7">
          <Wordmark className="text-ink" />

          <nav aria-label="Main" className="hidden lg:block">
            <ul className="flex items-center gap-space-5">
              {NAV_LINKS.map((link) => (
                <li key={link.href}>
                  <Link href={link.href} className="t-body-s text-ink-2 hover:text-ink">
                    {link.label}
                  </Link>
                </li>
              ))}
            </ul>
          </nav>

          <div className="flex-1" />

          <div className="flex items-center gap-space-3">
            {/* Nothing at all while the first health check is in flight, so the bar does not
                flash a state before it is true. */}
            {status && (
              <>
                <span className="hidden lg:inline">
                  <StatusPill status={status} />
                </span>
                <span className="lg:hidden">
                  <StatusPill status={status} short />
                </span>
              </>
            )}

            {/* Sign-in ships in 7c; the slot is kept so the bar does not shift when it lands. */}

            {/* Renders the same markup whatever the theme; CSS picks the icon. No
                suppressHydrationWarning needed, because nothing here can mismatch. */}
            <ThemeToggle theme={theme} onToggle={toggle} />

            <span className="lg:hidden">
              <MobileMenu />
            </span>
          </div>
        </div>
      </PageContainer>
    </header>
  );
}
