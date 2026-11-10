"use client";

import Link from "next/link";

import { GoogleButton } from "@/components/auth/google-button";
import { useAuth } from "@/lib/auth/provider";
import { initialFor } from "@/lib/auth/state";
import { CAN_SIGN_IN } from "@/lib/env";

/**
 * The one slot in the header that changes with who is asking.
 *
 * Three states and one rule: whatever is in it is always the same height, so the bar never
 * jumps. Sign-in resolves asynchronously — a refresh round trip, or Google deciding whether
 * it recognises someone — and a header that reflows a second after it paints is the most
 * visible possible way to show that.
 *
 * Absent entirely when sign-in cannot work (no client id, or the flag off), which is the 7a
 * behaviour: a control that does nothing is worse than no control.
 */

export interface AuthSlotProps {
  /** Phone menu: fill the width and use the account row rather than the compact pill. */
  block?: boolean;
  /** Phone menu: close the sheet when the account row is followed. */
  onNavigate?: () => void;
}

export function AuthSlot({ block, onNavigate }: AuthSlotProps) {
  const { state, user } = useAuth();

  if (!CAN_SIGN_IN) return null;

  if (user) {
    return (
      <Link
        href="/account"
        onClick={onNavigate}
        className={block ? "ms-accountrow" : "ms-accountpill"}
        aria-label={`Your account, signed in as ${user.displayName}`}
      >
        <span className="ms-accountpill__initial" aria-hidden="true">
          {initialFor(user.displayName)}
        </span>
        <span className="ms-accountpill__name">{user.displayName}</span>
      </Link>
    );
  }

  // Resolving, or a sign-in in flight: a neutral shape of the right size, so the bar holds
  // its layout without claiming the visitor is either signed in or a guest.
  if (state.status === "RESOLVING" || state.status === "SIGNING_IN") {
    return (
      <span
        className="ms-skel"
        style={{ width: block ? "100%" : 220, height: 40, borderRadius: 999 }}
        aria-hidden="true"
      />
    );
  }

  return <GoogleButton width={block ? 320 : 220} />;
}
