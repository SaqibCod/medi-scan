"use client";

import { useState } from "react";
import { Dialog } from "@base-ui/react/dialog";
import Link from "next/link";

import { IconButton } from "@/components/ds";
import { FEATURE_SIGNIN } from "@/lib/env";
import { NAV_LINKS } from "./nav";

/**
 * The phone menu.
 *
 * Base UI's Dialog supplies the behaviour this has to get right and is easy to get wrong:
 * focus is trapped inside while it is open, Escape closes it, the page behind it does not
 * scroll, and focus returns to the button that opened it. None of the look comes from Base
 * UI — every colour and shape here is a token.
 */
export function MobileMenu() {
  const [open, setOpen] = useState(false);

  return (
    <Dialog.Root open={open} onOpenChange={setOpen}>
      <Dialog.Trigger
        render={<IconButton icon={open ? "close" : "menu"} label={open ? "Close menu" : "Open menu"} />}
      />

      <Dialog.Portal>
        <Dialog.Backdrop className="fixed inset-0 z-40 bg-bg/80 backdrop-blur-[var(--blur-bar)]" />

        <Dialog.Popup className="fixed inset-x-0 top-0 z-50 flex max-h-dvh flex-col gap-space-5 overflow-y-auto border-b border-line bg-bg px-space-4 pb-space-7 pt-space-4">
          <div className="flex items-center justify-end">
            <Dialog.Close render={<IconButton icon="close" label="Close menu" />} />
          </div>

          <Dialog.Title className="sr-only">Menu</Dialog.Title>

          <nav aria-label="Main">
            <ul className="flex flex-col">
              {NAV_LINKS.map((link) => (
                <li key={link.href}>
                  <Link
                    href={link.href}
                    onClick={() => setOpen(false)}
                    // Large targets, comfortably past the 44px minimum, because this is the
                    // only navigation a phone gets.
                    className="t-title flex min-h-[56px] items-center border-b border-line text-ink"
                  >
                    {link.label}
                  </Link>
                </li>
              ))}
            </ul>
          </nav>

          {/* Sign-in ships in 7c. The slot stays; the button does not appear until it works. */}
          {FEATURE_SIGNIN && null}
        </Dialog.Popup>
      </Dialog.Portal>
    </Dialog.Root>
  );
}
