import { afterEach, beforeEach, describe, expect, it } from "vitest";

import { createAuthChannel, type AuthChannel } from "./channel";
import { claimTabIdentity, ensureTabIdentity, TAB_ID_STORAGE_KEY } from "./tab-guard";
import { REFRESH_STORAGE_KEY } from "./tokens";

/**
 * The duplicated-tab guard (LLD A4, unit test 5).
 *
 * jsdom gives the whole file one `sessionStorage`, which is exactly the situation being
 * tested: "Duplicate tab" hands the copy the original's storage. Two `AuthChannel` instances
 * stand in for two tabs — which is sound precisely because a `BroadcastChannel` never
 * delivers to the instance that posted, so each one can only hear the other.
 */

const GUEST_KEY = "ms.guest";

/** A short timeout keeps the no-answer cases quick; the guard's real budget is 150 ms. */
const WAIT_MS = 60;

let channels: AuthChannel[] = [];

function openTab(): AuthChannel {
  const channel = createAuthChannel();
  channels.push(channel);
  return channel;
}

beforeEach(() => {
  window.sessionStorage.setItem(REFRESH_STORAGE_KEY, "refresh-token-from-the-original");
  window.sessionStorage.setItem(GUEST_KEY, '{"token":"guest-abc","expiresAt":"2099-01-01T00:00:00Z"}');
});

afterEach(() => {
  channels.forEach((channel) => channel.close());
  channels = [];
});

describe("a tab that was opened, not copied", () => {
  it("has no inherited id, so it is never a duplicate and keeps its token", async () => {
    const tab = openTab();

    const identity = await claimTabIdentity(tab, WAIT_MS);

    expect(identity.duplicated).toBe(false);
    expect(window.sessionStorage.getItem(REFRESH_STORAGE_KEY)).toBe(
      "refresh-token-from-the-original",
    );
  });

  it("writes nothing at all for a visitor who never signs in", async () => {
    window.sessionStorage.clear();

    const identity = await claimTabIdentity(openTab(), WAIT_MS);

    // An id only ever exists to protect a refresh token. A guest has none, so storing an
    // identifier for them would be tracking them for nothing.
    expect(identity.tabId).toBeNull();
    expect(window.sessionStorage.length).toBe(0);
  });

  it("takes an id only once it holds a token, and then answers for it", async () => {
    const tab = openTab();
    await claimTabIdentity(tab, WAIT_MS);
    const tabId = ensureTabIdentity(tab);

    expect(window.sessionStorage.getItem(TAB_ID_STORAGE_KEY)).toBe(tabId);

    // Asking twice does not mint a second id.
    expect(ensureTabIdentity(tab)).toBe(tabId);
  });

  it("answers for its own id once it holds one", async () => {
    const tab = openTab();
    await claimTabIdentity(tab, WAIT_MS);
    const original = { tabId: ensureTabIdentity(tab) };

    // A second tab asking about that id must now be told it is taken.
    const asker = openTab();
    const answers: string[] = [];
    asker.subscribe((message) => {
      if (message.type === "tab-taken") answers.push(message.tabId);
    });

    asker.post({ type: "tab-claim", tabId: original.tabId });
    await new Promise((done) => setTimeout(done, WAIT_MS));

    expect(answers).toEqual([original.tabId]);
  });
});

describe("a duplicated tab", () => {
  it("drops the copied refresh token, keeps the guest token, and takes a new id", async () => {
    // The original tab starts, signs in, and so takes an id.
    const original = openTab();
    await claimTabIdentity(original, WAIT_MS);
    const firstId = ensureTabIdentity(original);

    // "Duplicate tab": the copy opens with the same sessionStorage, so it inherits both the
    // tab id and the refresh token.
    const copy = openTab();
    const second = await claimTabIdentity(copy, WAIT_MS);

    expect(second.duplicated).toBe(true);
    expect(firstId).toBeTruthy();

    // The one thing that must go, because it rotates and would trip reuse detection.
    expect(window.sessionStorage.getItem(REFRESH_STORAGE_KEY)).toBeNull();

    // The one thing that must stay: a guest token does not rotate, so sharing it is harmless.
    expect(window.sessionStorage.getItem(GUEST_KEY)).toContain("guest-abc");

    // And it stops impersonating the original, taking an id of its own only if it signs in.
    expect(second.tabId).toBeNull();
    expect(window.sessionStorage.getItem(TAB_ID_STORAGE_KEY)).toBeNull();

    const freshId = ensureTabIdentity(copy);
    expect(freshId).not.toBe(firstId);
  });

  it("leaves the original tab alone: it is the copy that gives way", async () => {
    const original = openTab();
    await claimTabIdentity(original, WAIT_MS);
    const firstId = ensureTabIdentity(original);

    const second = await claimTabIdentity(openTab(), WAIT_MS);
    expect(second.duplicated).toBe(true);

    // The original is still the owner of its id and still answering for it. Checked by asking
    // again rather than by reading storage: a real copy has its own `sessionStorage`, which
    // jsdom cannot model — here both "tabs" share one, so what the copy cleared would look
    // like the original's loss. What the original actually keeps is its answerer.
    const asker = openTab();
    const answers: string[] = [];
    asker.subscribe((message) => {
      if (message.type === "tab-taken") answers.push(message.tabId);
    });

    asker.post({ type: "tab-claim", tabId: firstId });
    await new Promise((done) => setTimeout(done, WAIT_MS));

    expect(answers).toEqual([firstId]);
  });
});

describe("a single tab is never a duplicate", () => {
  it("does not answer its own claim after a reload", async () => {
    // A reload keeps sessionStorage, so the id is inherited — the same signal a copy has.
    // What a copy has and a reload does not is another live tab to answer.
    window.sessionStorage.setItem(TAB_ID_STORAGE_KEY, "id-from-before-the-reload");

    const identity = await claimTabIdentity(openTab(), WAIT_MS);

    expect(identity.duplicated).toBe(false);
    expect(identity.tabId).toBe("id-from-before-the-reload");
    expect(window.sessionStorage.getItem(REFRESH_STORAGE_KEY)).toBe(
      "refresh-token-from-the-original",
    );
  });

  it("is not fooled by a claim for some other tab's id", async () => {
    window.sessionStorage.setItem(TAB_ID_STORAGE_KEY, "mine");

    const other = openTab();
    other.subscribe((message) => {
      // An unrelated tab answering about its own id must not be read as an answer about ours.
      if (message.type === "tab-claim") other.post({ type: "tab-taken", tabId: "someone-else" });
    });

    const identity = await claimTabIdentity(openTab(), WAIT_MS);

    expect(identity.duplicated).toBe(false);
    expect(identity.tabId).toBe("mine");
  });
});

describe("without BroadcastChannel", () => {
  it("keeps the token rather than guessing, which is the pre-guard behaviour", async () => {
    window.sessionStorage.setItem(TAB_ID_STORAGE_KEY, "inherited");

    const dead: AuthChannel = {
      post() {},
      subscribe() {
        return () => {};
      },
      close() {},
      live: false,
    };

    const identity = await claimTabIdentity(dead, WAIT_MS);

    expect(identity.duplicated).toBe(false);
    expect(window.sessionStorage.getItem(REFRESH_STORAGE_KEY)).toBe(
      "refresh-token-from-the-original",
    );
  });
});

describe("the handshake carries nothing sensitive", () => {
  it("posts only a message type and an opaque tab id", async () => {
    window.sessionStorage.setItem(TAB_ID_STORAGE_KEY, "inherited-id");

    const watcher = openTab();
    const seen: unknown[] = [];
    watcher.subscribe((message) => seen.push(message));

    await claimTabIdentity(openTab(), WAIT_MS);

    expect(seen).toEqual([{ type: "tab-claim", tabId: "inherited-id" }]);

    const serialised = JSON.stringify(seen);
    expect(serialised).not.toContain("refresh-token-from-the-original");
    expect(serialised).not.toContain("guest-abc");
  });
});
