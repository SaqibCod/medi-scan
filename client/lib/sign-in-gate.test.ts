import { afterEach, describe, expect, it, vi } from "vitest";

import {
  hasActiveStream,
  isSigningIn,
  resetSignInGate,
  signInFinished,
  signInStarted,
  streamFinished,
  streamStarted,
  subscribeSignIn,
  whenNoStreamActive,
} from "./sign-in-gate";

/**
 * The handshake between signing in and an open conversation (7c pre-work).
 *
 * Two promises, and both are about not losing something the reader can see: a question is not
 * sent with a credential that is about to change, and an answer already arriving is not cut
 * off to apply that change a second sooner.
 */
describe("sign-in gate", () => {
  afterEach(() => resetSignInGate());

  it("reports when a sign-in is running", () => {
    expect(isSigningIn()).toBe(false);

    signInStarted();
    expect(isSigningIn()).toBe(true);

    signInFinished();
    expect(isSigningIn()).toBe(false);
  });

  it("notifies subscribers on both edges", () => {
    const listener = vi.fn();
    subscribeSignIn(listener);

    signInStarted();
    signInFinished();

    expect(listener).toHaveBeenCalledTimes(2);
  });

  it("does not notify twice for the same edge", () => {
    // 7c may call these from more than one place — a click handler and a callback — and the
    // panel should not re-render for a state it is already in.
    const listener = vi.fn();
    subscribeSignIn(listener);

    signInStarted();
    signInStarted();

    expect(listener).toHaveBeenCalledTimes(1);
  });

  it("stops notifying after unsubscribe", () => {
    const listener = vi.fn();
    subscribeSignIn(listener)();

    signInStarted();

    expect(listener).not.toHaveBeenCalled();
  });

  it("resolves immediately when nothing is streaming", async () => {
    await expect(whenNoStreamActive()).resolves.toBeUndefined();
  });

  it("waits for an open stream to finish", async () => {
    streamStarted();
    expect(hasActiveStream()).toBe(true);

    let settled = false;
    const waiting = whenNoStreamActive().then(() => {
      settled = true;
    });

    // Still open, so nothing has resolved yet.
    await Promise.resolve();
    expect(settled).toBe(false);

    streamFinished();
    await waiting;
    expect(settled).toBe(true);
  });

  it("waits for every open stream, not just the first to finish", async () => {
    // A reader can press a suggestion chip while the previous answer is still arriving. A
    // boolean would let the first one to close report idle while the second was still going,
    // and 7c would swap the credential under it.
    streamStarted();
    streamStarted();

    let settled = false;
    const waiting = whenNoStreamActive().then(() => {
      settled = true;
    });

    streamFinished();
    await Promise.resolve();
    expect(settled).toBe(false);

    streamFinished();
    await waiting;
    expect(settled).toBe(true);
  });

  it("never counts below zero", () => {
    // A stream that fails calls finish from a `finally`, and a defensive extra call must not
    // make the counter negative and wedge the next wait for ever.
    streamFinished();
    streamFinished();
    expect(hasActiveStream()).toBe(false);

    streamStarted();
    expect(hasActiveStream()).toBe(true);
  });

  it("releases several waiters at once", async () => {
    streamStarted();
    const first = whenNoStreamActive();
    const second = whenNoStreamActive();

    streamFinished();

    await expect(Promise.all([first, second])).resolves.toEqual([undefined, undefined]);
  });
});
