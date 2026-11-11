/**
 * The Google Identity Services surface, typed and narrowed to what 7c uses.
 *
 * Four calls and one callback. Everything else GIS offers — the popup and redirect UX modes,
 * the authorization-code flows, native credentials — is deliberately absent: this app wants an
 * ID token and nothing else, and an unused flow is an unused attack surface.
 *
 * <strong>FedCM changed what can be known.</strong> `use_fedcm_for_prompt` is deprecated and
 * ignored — FedCM is simply on — and under it `prompt()`'s moment listener no longer reports
 * whether the prompt was displayed: `isNotDisplayed()` and `getNotDisplayedReason()` return
 * nothing, `getSkippedReason()` is gone, and even the display moment can arrive up to a minute
 * late. So nothing here waits on a notification to decide anything. The tab's own timer
 * decides when to stop resolving (`state.ts`), and a credential is accepted whenever it
 * arrives, however late.
 */

const GSI_SRC = "https://accounts.google.com/gsi/client";

export interface GoogleCredentialResponse {
  credential: string;
  select_by?: string;
}

export interface GoogleButtonOptions {
  type?: "standard" | "icon";
  theme?: "outline" | "filled_blue" | "filled_black";
  size?: "large" | "medium" | "small";
  text?: "signin_with" | "signup_with" | "continue_with" | "signin";
  shape?: "rectangular" | "pill" | "circle" | "square";
  logo_alignment?: "left" | "center";
  width?: number;
  locale?: string;
}

interface GoogleIdApi {
  initialize(config: {
    client_id: string;
    callback: (response: GoogleCredentialResponse) => void;
    auto_select?: boolean;
    cancel_on_tap_outside?: boolean;
    itp_support?: boolean;
  }): void;
  prompt(momentListener?: (notification: unknown) => void): void;
  renderButton(parent: HTMLElement, options: GoogleButtonOptions): void;
  disableAutoSelect(): void;
}

declare global {
  interface Window {
    google?: { accounts?: { id?: GoogleIdApi } };
  }
}

function idApi(): GoogleIdApi | null {
  return (typeof window !== "undefined" && window.google?.accounts?.id) || null;
}

export function isGoogleReady(): boolean {
  return idApi() !== null;
}

let loading: Promise<boolean> | null = null;

/**
 * Loads the GIS library once per tab.
 *
 * The nonce is set as a property rather than an attribute: browsers blank the `nonce` content
 * attribute after parsing, and only the property still carries the value. Under our
 * `'strict-dynamic'` policy a script created by already-trusted code would be allowed even
 * without it, but a browser that does not understand `'strict-dynamic'` falls back to the
 * nonce, and that is the browser this has to keep working for.
 *
 * Resolves `false` rather than rejecting when the script will not load — an ad blocker, an
 * offline tab, a network that drops it. Sign-in is then simply unavailable, which the UI shows
 * by having no button, and every other part of the app carries on as a guest.
 */
export function loadGoogleScript(nonce?: string): Promise<boolean> {
  if (typeof document === "undefined") return Promise.resolve(false);
  if (isGoogleReady()) return Promise.resolve(true);
  if (loading) return loading;

  loading = new Promise<boolean>((resolve) => {
    const existing = document.querySelector<HTMLScriptElement>(`script[src="${GSI_SRC}"]`);
    if (existing) {
      existing.addEventListener("load", () => resolve(isGoogleReady()), { once: true });
      existing.addEventListener("error", () => resolve(false), { once: true });
      return;
    }

    const script = document.createElement("script");
    script.src = GSI_SRC;
    script.async = true;
    script.defer = true;
    if (nonce) script.nonce = nonce;
    script.addEventListener("load", () => resolve(isGoogleReady()), { once: true });
    script.addEventListener("error", () => resolve(false), { once: true });
    document.head.appendChild(script);
  });

  return loading;
}

let initialized = false;
let prompted = false;

/**
 * Configures GIS for this tab. Safe to call again; only the first call does anything.
 *
 * `auto_select` is what signs a returning visitor back in without a click (contract 2.1).
 * `cancel_on_tap_outside: false` keeps One Tap from vanishing on a stray click at the moment
 * someone is reading it. `itp_support` turns on the upgraded One Tap flow in Safari and other
 * browsers with Intelligent Tracking Prevention, where it is off by default.
 */
export function initializeGoogle(
  clientId: string,
  onCredential: (idToken: string) => void,
): boolean {
  const api = idApi();
  if (!api) return false;
  if (initialized) return true;

  api.initialize({
    client_id: clientId,
    callback: (response) => {
      // The ID token is handed straight on and never kept here. It is not stored, not logged,
      // and not put on any object that outlives this call (dataflow 3.2).
      if (response?.credential) onCredential(response.credential);
    },
    auto_select: true,
    cancel_on_tap_outside: false,
    itp_support: true,
  });

  initialized = true;
  return true;
}

/**
 * Asks Google for a credential without a click, once per tab.
 *
 * Once, because this is a visible interruption — One Tap appears over the page — and because
 * the effect that calls it can legitimately run more than once (React re-mounts it in
 * development, and a cancelled run has to be redone). Google applies its own cool-down on top.
 *
 * The moment listener is passed but ignored, for the FedCM reasons above. It exists only so
 * that GIS has somewhere to deliver notifications instead of warning about their absence.
 */
export function promptGoogle(): void {
  if (prompted) return;
  const api = idApi();
  if (!api) return;

  prompted = true;
  api.prompt(() => {});
}

export function renderGoogleButton(parent: HTMLElement, options: GoogleButtonOptions): boolean {
  const api = idApi();
  if (!api) return false;
  api.renderButton(parent, options);
  return true;
}

/**
 * Stops Google signing the visitor straight back in.
 *
 * Called on every sign-out, in every tab. Without it, `auto_select` would hand the next page
 * load a credential and undo the thing the visitor just asked for (dataflow 3.5).
 */
export function disableGoogleAutoSelect(): void {
  idApi()?.disableAutoSelect();
}

/** Tests only. */
export function resetGoogleForTests(): void {
  initialized = false;
  prompted = false;
  loading = null;
}
