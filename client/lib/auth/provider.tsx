"use client";

import { useRouter } from "next/navigation";
import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useRef,
  useState,
  type ReactNode,
} from "react";
import { useQueryClient } from "@tanstack/react-query";

import { useToast } from "@/components/notices/toaster";
import { ApiError } from "../api-error";
import type { User } from "../api-types";
import { AUTH_COPY } from "../copy";
import { clearGuestSession, peekGuestSession } from "../guest-session";
import { formatCountdown, resolveResetsAt } from "../notices";
import { queryKeys } from "../query-client";
import { signInFinished, signInStarted, whenNoStreamActive } from "../sign-in-gate";
import { deleteMe, exchangeGoogleToken, revokeRefreshToken } from "./api";
import { getAuthChannel } from "./channel";
import { installAuthCredentials } from "./credential-provider";
import { disableGoogleAutoSelect, initializeGoogle, loadGoogleScript, promptGoogle } from "./google";
import { onSignedOut, refreshSession } from "./refresh";
import {
  GUEST,
  REFRESH_LEAD_MS,
  RESOLVE_BUDGET_MS,
  RESOLVING,
  SIGNING_IN,
  type AuthState,
} from "./state";
import { claimTabIdentity } from "./tab-guard";
import { clearTokens, readRefreshToken, storeTokens } from "./tokens";

/**
 * Who is asking, for the whole tab.
 *
 * The access token is not here and never will be — it lives in `tokens.ts` as a module
 * variable that nothing renders. What this holds is the part the UI legitimately needs: a
 * status, a display name, and when the token runs out, so it can be replaced before it does.
 */

// Installed on import rather than in an effect: child effects run before parent effects, so a
// page's first query would otherwise fire before the provider existed and go out as a guest.
installAuthCredentials();

export interface AuthContextValue {
  state: AuthState;
  user: User | null;
  /** True while a page should hold its report queries (contract 2.1), bounded to 2 seconds. */
  waiting: boolean;
  /** Sign-in is possible at all: a client id is configured and Google's script loaded. */
  googleReady: boolean;
  signInWith(idToken: string): Promise<void>;
  signOut(): Promise<void>;
  deleteAccount(): Promise<void>;
}

const AuthContext = createContext<AuthContextValue | null>(null);

/** How long to keep retrying a refresh that failed for a reason that might clear up. */
const RETRY_DELAYS_MS = [1_000, 2_000, 4_000, 8_000];

export function AuthProvider({
  children,
  clientId,
  nonce,
}: {
  children: ReactNode;
  clientId?: string;
  nonce?: string;
}) {
  const [state, setState] = useState<AuthState>(RESOLVING);
  const [budgetSpent, setBudgetSpent] = useState(false);
  const [googleReady, setGoogleReady] = useState(false);

  const queryClient = useQueryClient();
  const toast = useToast();
  const router = useRouter();

  const startedRef = useRef(false);

  /** Everything a tab drops when it stops being signed in, whatever the reason. */
  const becomeGuest = useCallback(() => {
    clearTokens();
    disableGoogleAutoSelect();
    setState(GUEST);
    // Report data is owner-scoped, so a cache built for a user must not be read by the guest
    // the tab just became. Removed rather than invalidated: there is nothing to refetch.
    queryClient.removeQueries({ queryKey: queryKeys.me });
    queryClient.removeQueries({ queryKey: queryKeys.trends });
    queryClient.removeQueries({ queryKey: queryKeys.reports });
  }, [queryClient]);

  // --- Signing in (dataflow 3.2) ------------------------------------------------------

  const signIn = useCallback(
    async (idToken: string) => {
      signInStarted();
      setState(SIGNING_IN);

      try {
        // An answer already streaming finishes under the credential it started with. Swapping
        // it mid-stream would abort a reply someone is in the middle of reading (LLD A10).
        await whenNoStreamActive();

        const guest = peekGuestSession();
        const response = await exchangeGoogleToken(idToken, guest?.token ?? null);
        const accessExpiresAt = storeTokens(response);

        // Those reports belong to the user now, and the guest session that owned them is gone
        // on the server too. Keeping the token would mean the next guest action resurrects a
        // session that owns nothing.
        clearGuestSession();

        setState({ status: "SIGNED_IN", user: response.user, accessExpiresAt });

        // Everything owner-scoped was fetched as a guest, or not at all.
        void queryClient.invalidateQueries({ queryKey: queryKeys.reports });
        void queryClient.invalidateQueries({ queryKey: queryKeys.trends });
        void queryClient.invalidateQueries({ queryKey: queryKeys.me });

        const claimed = response.claimedReportCount;
        toast({
          icon: "user",
          title: response.newUser
            ? AUTH_COPY.welcomeTitle(response.user.displayName)
            : AUTH_COPY.signedInTitle(response.user.displayName),
          body:
            claimed > 0
              ? response.newUser
                ? AUTH_COPY.welcomeClaim(claimed)
                : AUTH_COPY.signedInClaim(claimed)
              : undefined,
        });
      } catch (error) {
        setState(GUEST);

        if (error instanceof ApiError && error.code === "RATE_LIMITED") {
          toast({
            icon: "clock",
            title: AUTH_COPY.signInRateLimited(
              formatCountdown(resolveResetsAt(error.retryAfterSec)),
            ),
          });
        } else if (error instanceof ApiError && error.code === "NETWORK_ERROR") {
          toast({
            icon: "clock",
            title: AUTH_COPY.signInOfflineTitle,
            body: AUTH_COPY.signInOfflineBody,
          });
        } else {
          toast({ icon: "user", title: AUTH_COPY.signInFailedTitle });
        }
      } finally {
        // The ID token goes out of scope here. It was never stored and never logged.
        signInFinished();
      }
    },
    [queryClient, toast],
  );

  // Kept in a ref so the once-per-tab start effect below can reach the current version
  // without listing it as a dependency and re-running the whole sequence.
  const signInRef = useRef(signIn);
  // Declared before the start effect below, so it has the current version by the time Google
  // could possibly call back.
  useEffect(() => {
    signInRef.current = signIn;
  }, [signIn]);

  // --- Tab start (dataflow 3.3) -------------------------------------------------------

  useEffect(() => {
    if (startedRef.current) return;
    startedRef.current = true;

    let cancelled = false;
    const budget = setTimeout(() => setBudgetSpent(true), RESOLVE_BUDGET_MS);

    async function startGoogle() {
      if (!clientId) {
        if (process.env.NODE_ENV === "development") {
          // Once, and in development only: a missing client id is a setup mistake, not
          // something a visitor can do anything about.
          console.warn("[auth] NEXT_PUBLIC_GOOGLE_CLIENT_ID is not set; sign-in is unavailable.");
        }
        return;
      }

      const loaded = await loadGoogleScript(nonce);
      if (cancelled || !loaded) return;

      const ready = initializeGoogle(clientId, (idToken) => {
        // Arrives whenever Google decides, which under FedCM can be well after the tab gave
        // up waiting. A late credential is still a sign-in; the queries re-run after it.
        void signInRef.current(idToken);
      });
      if (!ready) return;

      setGoogleReady(true);
      promptGoogle();
    }

    async function resolve() {
      // Before touching any token: is this tab a copy of another one? (LLD A4)
      await claimTabIdentity(getAuthChannel());
      if (cancelled) return;

      if (readRefreshToken()) {
        for (let attempt = 0; attempt <= RETRY_DELAYS_MS.length; attempt += 1) {
          try {
            const session = await refreshSession();
            if (cancelled) return;

            if (session) {
              setState({ status: "SIGNED_IN", ...session });
              return;
            }

            // Rejected outright. The tokens are already gone; carry on as a guest and let
            // Google decide whether this is someone it recognises.
            break;
          } catch {
            // A refresh that could not be delivered says nothing about whether the token is
            // good, so the tab stays RESOLVING rather than claiming to be signed out. Pages
            // render once the budget runs out; the header keeps its neutral slot.
            const delay = RETRY_DELAYS_MS[attempt];
            if (delay === undefined || cancelled) return;
            await new Promise((done) => setTimeout(done, delay));
            if (cancelled) return;
          }
        }
      }

      if (cancelled) return;
      setState(GUEST);
      await startGoogle();
    }

    void resolve();

    return () => {
      cancelled = true;
      clearTimeout(budget);
    };
    // Once per tab. Everything it reaches for is either a prop fixed at build time or held in
    // a ref, so there is nothing here that could go stale.
  }, [clientId, nonce]);

  // --- Signing out, everywhere (dataflow 3.5) -----------------------------------------

  const signOut = useCallback(async () => {
    const token = readRefreshToken();

    // Local state first: from this moment no call in this tab can go out under the old token.
    becomeGuest();
    getAuthChannel().post({ type: "logout" });

    router.push("/");
    toast({ icon: "user", title: AUTH_COPY.signedOutTitle });

    if (!token) return;
    try {
      await revokeRefreshToken(token);
    } catch {
      // The contract answers 204 even for a token it has never seen, so this only fails when
      // the request never arrived. The tab is signed out either way and the family expires on
      // its own within 7 days.
    }
  }, [becomeGuest, router, toast]);

  // Another tab started it. This one revokes its own token and nothing else: refresh tokens
  // never cross the channel, and a tab on Account or Trends just shows its signed-out view.
  useEffect(() => {
    return getAuthChannel().subscribe((message) => {
      if (message.type !== "logout") return;

      const token = readRefreshToken();
      becomeGuest();
      if (token) void revokeRefreshToken(token).catch(() => {});
    });
  }, [becomeGuest]);

  // --- Deleting the account (dataflow 8.1) --------------------------------------------

  const deleteAccount = useCallback(async () => {
    await deleteMe();

    // The same as signing out everywhere, minus the logout call — the server has already
    // destroyed every family — and minus the navigation, so the page can show its signed-out
    // state in place (LLD 10, e2e 5).
    becomeGuest();
    getAuthChannel().post({ type: "logout" });

    toast({
      icon: "trash",
      title: AUTH_COPY.accountDeletedTitle,
      body: AUTH_COPY.accountDeletedBody,
    });
  }, [becomeGuest, toast]);

  // --- Proactive refresh (LLD A5) -----------------------------------------------------

  useEffect(() => {
    if (state.status !== "SIGNED_IN") return;

    // An expiry we could not parse is stored as 0. Scheduling off it would fire immediately,
    // and again after every refresh, so such a token is simply used until the server rejects
    // it.
    if (state.accessExpiresAt <= 0) return;

    const due = Math.max(0, state.accessExpiresAt - REFRESH_LEAD_MS - Date.now());

    const timer = setTimeout(() => {
      void (async () => {
        try {
          const session = await refreshSession();
          // `null` means the server rejected it, which `onSignedOut` below has already acted
          // on. Nothing more to do here.
          if (session) setState({ status: "SIGNED_IN", ...session });
        } catch {
          // Offline, or the backend is asleep. The token in memory is still the best one this
          // tab has, and may well outlive the outage.
        }
      })();
    }, due);

    return () => clearTimeout(timer);
  }, [state]);

  // The refresh token was rejected — at any moment, and possibly with nobody waiting on it.
  useEffect(() => {
    return onSignedOut(() => {
      becomeGuest();
      toast({
        icon: "clock",
        title: AUTH_COPY.sessionEndedTitle,
        body: AUTH_COPY.sessionEndedBody,
        action: { label: AUTH_COPY.sessionEndedAction, onClick: () => router.push("/account") },
      });
    });
  }, [becomeGuest, router, toast]);

  const value = useMemo<AuthContextValue>(
    () => ({
      state,
      user: state.status === "SIGNED_IN" ? state.user : null,
      waiting: state.status === "RESOLVING" && !budgetSpent,
      googleReady,
      signInWith: signIn,
      signOut,
      deleteAccount,
    }),
    [state, budgetSpent, googleReady, signIn, signOut, deleteAccount],
  );

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth(): AuthContextValue {
  const value = useContext(AuthContext);
  if (!value) throw new Error("useAuth must be used inside <AuthProvider>");
  return value;
}
