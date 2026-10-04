import Link from "next/link";
import { Activity, LogIn } from "lucide-react";

import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { ModeToggle } from "@/components/ui/mode-toggle";

const NAV_LINKS = [
  { href: "/biomarkers", label: "Biomarkers" },
  { href: "/history", label: "History" },
] as const;

export function SiteHeader() {
  return (
    <header className="sticky top-0 z-40 border-b border-border bg-background/85 backdrop-blur supports-backdrop-filter:bg-background/70">
      <div className="mx-auto flex h-14 max-w-5xl items-center gap-3 px-4 sm:px-6">
        <Link
          href="/"
          className="flex shrink-0 items-center gap-2 rounded-sm font-semibold tracking-tight focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2"
        >
          <Activity aria-hidden="true" className="size-5 text-primary" />
          <span translate="no">Medi&#8209;Scan</span>
        </Link>

        <nav aria-label="Main" className="min-w-0">
          <ul className="flex items-center gap-1">
            {NAV_LINKS.map((link) => (
              <li key={link.href}>
                <Link
                  href={link.href}
                  className="rounded-md px-2.5 py-1.5 text-sm text-muted-foreground transition-colors hover:bg-muted hover:text-foreground focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
                >
                  {link.label}
                </Link>
              </li>
            ))}
          </ul>
        </nav>

        <div className="ml-auto flex shrink-0 items-center gap-2">
          {/*
            Sign-in arrives in phase 6. The button is genuinely disabled rather than
            present-but-broken, and the reason sits next to it as visible text — a disabled
            control is not focusable, so a tooltip alone would hide the explanation from
            keyboard and screen-reader users.

            No Google wordmark or "G" logo until the button actually signs you in with
            Google; branding on a dead control is a small lie.
          */}
          {/*
            `sr-only sm:not-sr-only`, not `hidden sm:inline-flex`: `hidden` is display:none,
            which drops the element from the accessibility tree, so on a narrow screen the
            aria-describedby below would point at nothing and the explanation would vanish for
            exactly the users who most need it.
          */}
          <Badge
            id="signin-status"
            variant="secondary"
            className="sr-only sm:not-sr-only sm:inline-flex"
          >
            Sign&#8209;in coming soon
          </Badge>
          <Button variant="outline" size="sm" disabled aria-describedby="signin-status">
            <LogIn aria-hidden="true" />
            Sign In
          </Button>

          <ModeToggle />
        </div>
      </div>
    </header>
  );
}
