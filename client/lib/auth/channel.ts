/**
 * The one `BroadcastChannel` this app opens, and the two conversations it carries.
 *
 * <strong>Exactly one instance per tab.</strong> A `BroadcastChannel` never delivers a message
 * to the instance that posted it, but it does deliver to another instance in the same tab. The
 * duplicated-tab guard depends on "nobody answered" meaning "no other tab", so a second
 * instance here would make every tab answer itself and declare itself a copy. Hence the
 * singleton, and hence tests build their own instances to play the part of a second tab.
 *
 * <strong>Nothing sensitive crosses it.</strong> Messages carry a message type and, for the
 * guard, an opaque random tab id. No token, no user, no report id, by rule (7c LLD 8).
 */

export const AUTH_CHANNEL_NAME = "ms-auth";

export type AuthMessage =
  /** Sign out, everywhere. Deliberately carries nothing: each tab revokes its own token. */
  | { type: "logout" }
  /** "Is this tab id already taken?", asked once on start by a tab that inherited one. */
  | { type: "tab-claim"; tabId: string }
  /** "Yes, that one is mine." Only the tab that owns the id answers. */
  | { type: "tab-taken"; tabId: string };

export interface AuthChannel {
  post(message: AuthMessage): void;
  /** Returns an unsubscribe function. */
  subscribe(listener: (message: AuthMessage) => void): () => void;
  close(): void;
  /** False where `BroadcastChannel` is missing, so callers can degrade knowingly. */
  readonly live: boolean;
}

const NO_CHANNEL: AuthChannel = {
  post() {},
  subscribe() {
    return () => {};
  },
  close() {},
  live: false,
};

function isAuthMessage(value: unknown): value is AuthMessage {
  if (!value || typeof value !== "object") return false;
  const type = (value as { type?: unknown }).type;
  return type === "logout" || type === "tab-claim" || type === "tab-taken";
}

/**
 * Opens a channel, or returns a dead one where the API is missing.
 *
 * Without `BroadcastChannel` — older Safari, and any browser with it disabled — logout stops
 * reaching other tabs and the duplicated-tab guard cannot run. Both degrade to the behaviour
 * that existed before either feature: each tab signs itself out, and a duplicated tab costs
 * one sign-out. Neither is a reason to refuse to sign anyone in.
 */
export function createAuthChannel(): AuthChannel {
  if (typeof window === "undefined" || typeof window.BroadcastChannel !== "function") {
    return NO_CHANNEL;
  }

  let channel: BroadcastChannel;
  try {
    channel = new window.BroadcastChannel(AUTH_CHANNEL_NAME);
  } catch {
    return NO_CHANNEL;
  }

  const listeners = new Set<(message: AuthMessage) => void>();

  channel.onmessage = (event: MessageEvent) => {
    // Another origin's page, or a future version of ours, may put something else on this
    // channel. An unrecognised shape is ignored rather than crashing a listener.
    if (!isAuthMessage(event.data)) return;
    listeners.forEach((listener) => listener(event.data as AuthMessage));
  };

  return {
    post(message) {
      try {
        channel.postMessage(message);
      } catch {
        // The channel was closed under us, by a page already on its way out.
      }
    },
    subscribe(listener) {
      listeners.add(listener);
      return () => listeners.delete(listener);
    },
    close() {
      listeners.clear();
      try {
        channel.close();
      } catch {
        // Already closed.
      }
    },
    live: true,
  };
}

let shared: AuthChannel | null = null;

/** The tab's single channel, opened on first use. */
export function getAuthChannel(): AuthChannel {
  shared ??= createAuthChannel();
  return shared;
}

/** Tests only. */
export function resetAuthChannelForTests(): void {
  shared?.close();
  shared = null;
}
