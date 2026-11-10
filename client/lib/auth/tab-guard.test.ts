import { afterEach, beforeEach, describe, expect, it } from "vitest";

import { createAuthChannel, type AuthChannel } from "./channel";
import { claimTabIdentity, TAB_ID_STORAGE_KEY } from "./tab-guard";
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
    expect(identity.tabId).toBeTruthy();
    expect(window.sessionStorage.getItem(TAB_ID_STORAGE_KEY)).toBe(identity.tabId);
    expect(window.sessionStorage.getItem(REFRESH_STORAGE_KEY)).toBe(
      "refresh-token-from-the-original",
    );
  });

  it("answers for its own id once it holds one", async () => {
    const original = await claimTabIdentity(openTab(), WAIT_MS);

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
    // The original tab starts and takes an id.
    const original = openTab();
    const first = await claimTabIdentity(original, WAIT_MS);

    // "Duplicate tab": the copy opens with the same sessionStorage, so it inherits both the
    // tab id and the refresh token.
    const copy = openTab();
    const second = await claimTabIdentity(copy, WAIT_MS);

    expect(second.duplicated).toBe(true);

    // The one thing that must go, because it rotates and would trip reuse detection.
    expect(window.sessionStorage.getItem(REFRESH_STORAGE_KEY)).toBeNull();

    // The one thing that must stay: a guest token does not rotate, so sharing it is harmless.
    expect(window.sessionStorage.getItem(GUEST_KEY)).toContain("guest-abc");

    // And it stops impersonating the original.
    expect(second.tabId).not.toBe(first.tabId);
    expect(window.sessionStorage.getItem(TAB_ID_STORAGE_KEY)).toBe(second.tabId);
  });

  it("leaves the original tab alone", async () => {
    const original = openTab();
    const first = await claimTabIdentity(original, WAIT_MS);

    await claimTabIdentity(openTab(), WAIT_MS);

    // The original keeps the id it claimed. Nothing in the handshake writes to another tab.
    expect(first.duplicated).toBe(false);
    expect(first.tabId).toBeTruthy();
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
