"use client";

import { useEffect, useRef } from "react";

import { AUTH_COPY } from "@/lib/copy";
import { renderGoogleButton, type GoogleButtonOptions } from "@/lib/auth/google";
import { useAuth } from "@/lib/auth/provider";
import { useThemeChoice } from "@/lib/use-theme-choice";

/**
 * Google's own "Sign in with Google" button (LLD A1).
 *
 * <strong>This is deliberately not our button.</strong> The mockups draw a pill with our own
 * icon and type, and we cannot ship that: the ID-token flow cannot be started from an element
 * we drew — only Google's iframe can hand the page a credential — and Google's branding terms
 * require their button anyway. So the pill in the design becomes the slot this sits in, and
 * the button inside it is theirs, styled through the handful of options they document.
 *
 * The container reserves the button's size, because GIS fills it a moment after the rest of
 * the page has painted and a header that jumps when it arrives is worse than one that waits.
 */

/** Google's own rendered heights, reserved so the layout does not shift when it lands. */
const HEIGHTS: Record<NonNullable<GoogleButtonOptions["size"]>, number> = {
  large: 40,
  medium: 32,
  small: 20,
};

export interface GoogleButtonProps {
  /** Pixels. Google clamps this to between about 200 and 400 for a standard button. */
  width?: number;
  size?: NonNullable<GoogleButtonOptions["size"]>;
  className?: string;
}

export function GoogleButton({ width = 220, size = "large", className }: GoogleButtonProps) {
  const { googleReady } = useAuth();
  const { theme } = useThemeChoice();
  const slot = useRef<HTMLDivElement>(null);

  useEffect(() => {
    const element = slot.current;
    if (!element || !googleReady) return;

    // GIS renders into the node and never updates what it rendered, so a theme change means
    // throwing the old button away and asking for a new one. Without this, switching to light
    // leaves a dark button sitting in a light header.
    element.replaceChildren();

    renderGoogleButton(element, {
      type: "standard",
      // Google offers no "follows the host page" option, so the closest match in each theme:
      // their outline button reads as light, filled_black as dark.
      theme: theme === "light" ? "outline" : "filled_black",
      size,
      text: "signin_with",
      shape: "pill",
      logo_alignment: "left",
      width,
    });

    return () => element.replaceChildren();
  }, [googleReady, theme, size, width]);

  return (
    <div
      ref={slot}
      className={className}
      style={{ width, minHeight: HEIGHTS[size] }}
      // The slot is empty until GIS fills it; without a name it is an unlabelled blank to a
      // screen reader during that window. Google's button brings its own name once rendered.
      aria-label={googleReady ? undefined : AUTH_COPY.signInLabel}
      aria-busy={!googleReady}
      suppressHydrationWarning
    />
  );
}
