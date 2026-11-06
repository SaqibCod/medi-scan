"use client";

/**
 * The handshake between signing in and a chat that is mid-conversation (7c pre-work).
 *
 * Google sign-in happens without a page reload, so the chat panel is still mounted and its
 * conversation is still in memory throughout. That is the behaviour we want — a reader who
 * signs in to keep their report should not lose the questions they just asked — but it means
 * two things have to be coordinated, and neither side should have to know about the other.
 *
 * <strong>Asking pauses while sign-in runs.</strong> A question sent mid-sign-in would carry
 * whichever credential happened to be current, and could arrive as a guest a moment after the
 * report stopped being a guest's — a 404 for a report the reader is looking at.
 *
 * <strong>A streaming answer finishes first.</strong> Swapping the credential under an open
 * SSE connection would abort it, losing an answer the reader is already reading for no reason
 * they could understand. 7c awaits {@link whenNoStreamActive} before applying the switch.
 *
 * The report id does not change when a guest report is claimed — only its owner column does —
 * so nothing here has to re-point the conversation at a new report.
 *
 * A reload still loses the conversation. That is what "Not saved" means, and it is the one
 * case this module deliberately does not try to rescue.
 */

type Listener = () => void;

let signingIn = false;
let activeStreams = 0;

const signInListeners = new Set<Listener>();
const idleWaiters = new Set<() => void>();

/** Called by 7c when a sign-in begins. Asking is refused until it finishes. */
export function signInStarted(): void {
  if (signingIn) return;
  signingIn = true;
  signInListeners.forEach((listener) => listener());
}

/** Called by 7c once the account context has been applied, successfully or not. */
export function signInFinished(): void {
  if (!signingIn) return;
  signingIn = false;
  signInListeners.forEach((listener) => listener());
}

export function isSigningIn(): boolean {
  return signingIn;
}

/** Subscribes to sign-in starting or finishing. Returns an unsubscribe function. */
export function subscribeSignIn(listener: Listener): () => void {
  signInListeners.add(listener);
  return () => signInListeners.delete(listener);
}

/** Chat calls this when a stream opens. */
export function streamStarted(): void {
  activeStreams += 1;
}

/**
 * Chat calls this when a stream closes, however it closed.
 *
 * Counted rather than a boolean: a reader can press a suggestion chip while the previous
 * answer is still finishing, and a boolean would let the first one to close report idle while
 * the second was still running.
 */
export function streamFinished(): void {
  activeStreams = Math.max(0, activeStreams - 1);
  if (activeStreams === 0) {
    const waiting = [...idleWaiters];
    idleWaiters.clear();
    waiting.forEach((resolve) => resolve());
  }
}

export function hasActiveStream(): boolean {
  return activeStreams > 0;
}

/**
 * Resolves once no answer is streaming.
 *
 * 7c awaits this between verifying the Google token and swapping the credential, so an
 * answer already on its way finishes under the credential it started with.
 */
export function whenNoStreamActive(): Promise<void> {
  if (activeStreams === 0) return Promise.resolve();
  return new Promise<void>((resolve) => idleWaiters.add(resolve));
}

/** Tests only. */
export function resetSignInGate(): void {
  signingIn = false;
  activeStreams = 0;
  signInListeners.clear();
  idleWaiters.clear();
}
