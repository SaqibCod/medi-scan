import { clearRefreshToken } from "./tokens";
import type { AuthChannel } from "./channel";

/**
 * The duplicated-tab guard (7c LLD A4).
 *
 * "Duplicate tab" — and Safari's "Open in New Tab" from a tab's own context menu — copies
 * `sessionStorage`. The copy therefore opens already holding the original's refresh token,
 * which nothing in the page asked for and nothing on the server can distinguish from theft:
 * refresh tokens rotate, so whichever tab refreshes second presents one that has already been
 * used, and reuse detection correctly revokes the family and signs the person out of both
 * tabs. They did nothing wrong and would have no idea why.
 *
 * So before a tab uses any token it asks, once, whether another live tab already owns its tab
 * id. The id is copied along with everything else, which is exactly what makes the question
 * answerable.
 *
 * <strong>Why a lone tab can never fail this.</strong> Three things have to hold, and all
 * three do. A `BroadcastChannel` never delivers a message to the instance that posted it, so a
 * tab cannot hear its own claim. There is exactly one channel instance per tab (see
 * `channel.ts`), so it cannot hear itself through a second one. And a tab only answers for the
 * id it is holding at that moment, which the asker is only asking about because it inherited
 * it. Silence is therefore the normal case, and it costs 150 ms once per tab.
 */

export const TAB_ID_STORAGE_KEY = "ms.tab";

/**
 * How long to wait for an answer.
 *
 * `BroadcastChannel` delivery is a task on another tab's event loop, not a network call, so
 * this is generous already. It is also pure latency on every tab start, which is why it is not
 * more generous: a missed answer costs one sign-out, a slow start costs every visitor.
 */
export const DUPLICATE_CHECK_MS = 150;

export interface TabIdentity {
  /** Null while the tab has never held a refresh token, and so has nothing to protect. */
  tabId: string | null;
  /** True when this tab was a copy and has had its inherited refresh token taken away. */
  duplicated: boolean;
}

function newTabId(): string {
  // Opaque and random. It identifies a tab to other tabs of the same origin for the length of
  // one session and means nothing to the server, which never sees it.
  if (typeof crypto !== "undefined" && typeof crypto.randomUUID === "function") {
    return crypto.randomUUID();
  }
  return `${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 10)}`;
}

function readTabId(): string | null {
  if (typeof window === "undefined") return null;
  try {
    return window.sessionStorage.getItem(TAB_ID_STORAGE_KEY);
  } catch {
    return null;
  }
}

function writeTabId(tabId: string): void {
  if (typeof window === "undefined") return;
  try {
    window.sessionStorage.setItem(TAB_ID_STORAGE_KEY, tabId);
  } catch {
    // Storage blocked. Without a stored id the next load looks like a fresh tab, which is the
    // safe direction to fail: a fresh tab is never treated as a duplicate.
  }
}

function clearTabId(): void {
  if (typeof window === "undefined") return;
  try {
    window.sessionStorage.removeItem(TAB_ID_STORAGE_KEY);
  } catch {
    // Nothing was stored.
  }
}

/**
 * Starts answering claims for `tabId`, for as long as this tab holds it.
 *
 * Deliberately not torn down on unload: a tab that is going away stops answering because its
 * channel dies with the document, and a tab that is merely hidden must keep answering — a
 * background tab is still a live tab holding a live refresh token.
 */
function answerFor(channel: AuthChannel, tabId: string): () => void {
  return channel.subscribe((message) => {
    if (message.type === "tab-claim" && message.tabId === tabId) {
      channel.post({ type: "tab-taken", tabId });
    }
  });
}

/**
 * Takes an id for this tab, if it does not already have one, and starts answering for it.
 *
 * Called when the tab first holds a refresh token — at sign-in, and after the refresh that
 * restores a session on reload. Not on page load: a visitor who never signs in has no token
 * to protect, and writing them an identifier anyway would be tracking them for nothing. Most
 * visitors are guests, and for them `sessionStorage` stays completely empty.
 */
export function ensureTabIdentity(channel: AuthChannel): string {
  const existing = readTabId();
  if (existing) return existing;

  const tabId = newTabId();
  writeTabId(tabId);
  answerFor(channel, tabId);
  return tabId;
}

/**
 * Works out whether this tab is a copy, and takes the inherited token away if it is.
 *
 * Returns once the question is settled, which is immediately for a tab that has no inherited
 * id at all — the overwhelmingly common case of a genuinely new tab, and of every tab
 * belonging to someone who has never signed in.
 */
export async function claimTabIdentity(
  channel: AuthChannel,
  timeoutMs: number = DUPLICATE_CHECK_MS,
): Promise<TabIdentity> {
  const inherited = readTabId();

  // No stored id means there was no signed-in tab to copy. An id is only ever written
  // alongside a refresh token, so without one there is nothing that could have been
  // duplicated, and nothing to ask about.
  if (!inherited) return { tabId: null, duplicated: false };

  // Without a working channel the question cannot be asked. Keeping the token is the
  // pre-guard behaviour: a duplicate costs one sign-out, a lone tab keeps working.
  if (!channel.live) {
    answerFor(channel, inherited);
    return { tabId: inherited, duplicated: false };
  }

  const taken = await new Promise<boolean>((resolve) => {
    let settled = false;

    const stop = channel.subscribe((message) => {
      if (settled) return;
      if (message.type === "tab-taken" && message.tabId === inherited) {
        settled = true;
        stop();
        clearTimeout(timer);
        resolve(true);
      }
    });

    const timer = setTimeout(() => {
      if (settled) return;
      settled = true;
      stop();
      resolve(false);
    }, timeoutMs);

    channel.post({ type: "tab-claim", tabId: inherited });
  });

  if (!taken) {
    answerFor(channel, inherited);
    return { tabId: inherited, duplicated: false };
  }

  // A copy. The refresh token goes, and only the refresh token: `ms.guest` is a shared,
  // non-rotating credential that two tabs can hold without either of them noticing.
  clearRefreshToken();

  // And it stops impersonating the original. It takes an id of its own only if it signs in
  // again, which keeps the rule intact: an id exists exactly when a token does.
  clearTabId();
  return { tabId: null, duplicated: true };
}
