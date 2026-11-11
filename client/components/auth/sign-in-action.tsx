"use client";

import { GoogleButton } from "@/components/auth/google-button";
import { useAuth } from "@/lib/auth/provider";
import { CAN_SIGN_IN } from "@/lib/env";

/**
 * "Sign in with Google", wherever a screen offers it as one action among several.
 *
 * Absent for someone already signed in — the mockups put it beside "My reports", and offering
 * to sign in a person who is signed in is noise — and absent when sign-in cannot work at all.
 *
 * The mockups draw it as our own outline pill. It is Google's button instead (LLD A1): the
 * credential can only come from their iframe. Width is at Google's lower bound so it sits in
 * a row of buttons without dominating it.
 */
export function SignInAction({ width = 200 }: { width?: number }) {
  const { user, state } = useAuth();

  if (!CAN_SIGN_IN) return null;
  // Nothing while the answer is still unknown, rather than a button that may vanish a moment
  // later. These screens are already an interruption; a second one is not worth 2 seconds.
  if (user || state.status !== "GUEST") return null;

  return <GoogleButton width={width} />;
}
