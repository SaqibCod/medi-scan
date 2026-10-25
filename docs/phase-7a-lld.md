# Phase 7a: Frontend foundation (low-level design)

Frontend only (`client/`). No backend changes. Branch: `feat/phase-7a-frontend-foundation`, created from `dev`.

This phase builds everything the later frontend phases stand on:
- the design system in code
- the app shell
- the API client
- the guest session
- backend status
- error handling, banners and toasts
- the system screens and the privacy page

It does not build the landing page, upload, results, sign-in, history, trends, or admin. Those are 7b to 7d.

**References**
- `docs/design-system/`: the Medi-Scan design system (tokens, type, components, voice). This is the styling source of truth.
- `docs/ui/`: screenshots of the approved mockups, and which phase each screen belongs to.
- `docs/api-contract.md`: sections 1 (conventions, headers, errors), 2.1 and 2.2 (callers, guest sessions), 7 (health), 8 (error codes), 9 (types).
- Root `CLAUDE.md`: the "Frontend" rules (token storage, XSS, data fetching, errors).

---

## 1. Frontend phase plan

| Phase | Builds | Screens (`docs/ui/README.md`) |
|---|---|---|
| **7a** | Foundation: tokens, components, shell, API client, guest session, status, errors, toasts, system screens, privacy page | System screens, Privacy |
| **7b** | Landing page, upload panel, samples, report processing, results and failed states, biomarker explainer page | Landing, Report (no chat), Biomarker explainer |
| — | **Deploy a first version** (Vercel, AWS, Neon) | — |
| **7c** | Google sign-in, refresh, cross-tab logout, My reports, Account, Trends | My reports, Account, Trends |
| **7d** | Admin dashboard, accessibility pass, performance, polish | Admin |

Chat UI is built with Phase 5 (chat), on the report page from 7b.

---

## 2. Decisions (already made, confirm at the approval point)

| # | Decision | Reason |
|---|---|---|
| F1 | **The design system replaces generic styling.** Every colour, radius, shadow, blur, space and type style comes from `docs/design-system/tokens.json`. No raw hex values in components. | One look, two themes, checked contrast. |
| F2 | **Icons come only from the design-system `Icon` set.** Lucide is removed if Phase 1 added it. If a new icon is needed, add a path in the same style (24 grid, 1.6 stroke, round caps) and tell me. | The design system says no other icons. |
| F3 | **shadcn/ui only for behaviour primitives,** fully restyled with tokens: the mobile menu sheet (dialog behaviour, focus trap, Esc) and the toaster. No default shadcn look anywhere. | Accessible behaviour without fighting the design. |
| F4 | **Fonts are self-hosted** through `next/font` (Geist and Geist Mono). No request to Google Fonts at runtime. The `@import` in `bundle.css` is not copied. | Strict CSP, privacy, speed. |
| F5 | **Theme:** dark by default. It follows the device setting until the visitor toggles. The choice is saved in `localStorage` under `ms-theme`, with value `dark` or `light` only. | The root rule bans `localStorage` for **tokens**. A theme choice is not personal data. Add this exception to `CLAUDE.md` in plain words. |
| F6 | **No theme flash:** a tiny inline script sets `data-theme` on `<html>` before first paint, and carries the CSP nonce. | Dark default with a light preference must not flash. |
| F7 | **CSP:** nonce-based `script-src` (strict). `style-src 'self' 'unsafe-inline'`, for styles only. | React and Next write `style` attributes, which a strict style policy would block. Inline styles can't run code, so the token-theft risk is in scripts. Write this reason in `SECURITY.md`. |
| F8 | **Sign-in button hidden until 7c,** behind `NEXT_PUBLIC_FEATURE_SIGNIN` (default `false`). The header layout keeps its slot. | No fake buttons. |
| F9 | **The Helix renders only on the client,** after mount, and not at all under `prefers-reduced-motion` (a static version is fine). | Hundreds of dots shouldn't be in the server HTML. |
| F10 | **Screenshots are committed** to `docs/ui/screens/` (about 6 MB). The original HTML mockups stay out of git. | Claude Code and reviewers can see the designs. |
| F11 | **A dev-only component gallery** at `/dev/components` shows every component in both themes. In production it returns 404. | Visual checks against the design system without a Storybook dependency. |

---

## 3. Folder layout (`client/`)

```text
app/
  layout.tsx                 html, fonts, theme script, providers, shell
  providers.tsx              QueryClientProvider, ThemeProvider, StatusProvider, NoticeProvider, Toaster
  page.tsx                   placeholder home (replaced in 7b; see 9.4)
  privacy/page.tsx
  not-found.tsx              404
  error.tsx                  500 inside the shell
  global-error.tsx           last resort, no shell
  dev/components/page.tsx    gallery (dev only)
  styles/
    tokens.css               CSS variables, generated from tokens.json (both themes)
    ds.css                   component styles ported from bundle.css
    globals.css              Tailwind import, @theme mapping, base styles
components/
  ds/                        design-system components (section 5)
  shell/                     Header, MobileMenu, Footer, SkipLink, Disclaimer, PageContainer
  system/                    SystemScreen and the six screens (section 9)
  notices/                   WakingBanner, DailyLimitBanner, InlineNotice, Toaster wrapper
lib/
  api.ts                     the one API client (section 6)
  api-types.ts               contract types (exists, must match the contract)
  api-error.ts               ApiError and parsing
  error-messages.ts          code to UI wording
  guest-session.ts           lazy guest session (section 7)
  credentials.ts             credential provider interface (7c plugs in here)
  backend-status.ts          health checks and wake state (section 8)
  notices.ts                 banner state (daily limit, waking)
  theme.ts                   theme read, write, apply
  env.ts                     validated public env vars
scripts/
  build-tokens.mjs           tokens.json → app/styles/tokens.css
tests/                       Vitest unit tests (or co-located *.test.ts)
e2e/                         Playwright tests
```

Keep the names unless there's a real reason. Say so at approval point 2 if the Phase 1 layout differs.

---

## 4. Tokens, fonts and theme

### 4.1 Tokens to CSS

- `scripts/build-tokens.mjs` reads `docs/design-system/tokens.json` and writes `app/styles/tokens.css`:
  - `:root, [data-theme="dark"] { --bg: #0e0e10; … }` and `[data-theme="light"] { --bg: #f6f6f8; … }`, for every colour token and both shadows.
  - Single-value tokens: `--space-1 … --space-10`, `--radius-*`, `--blur-*`.
  - Type styles as variables: `--display-xl-size`, `--display-xl-line`, `--display-xl-weight`, `--display-xl-track`, and so on.
  - `--font-sans` and `--font-mono` point at the `next/font` variables.
- The generated file is committed. A unit test fails if it is stale (it runs the generator in memory and compares).
- **Tailwind:** map the tokens in `@theme` so utilities exist (`bg-bg`, `text-ink`, `text-ink-2`, `border-line`, `rounded-pill`, `p-space-5` or the equivalent naming). Use the current Tailwind version from Phase 1. Check the `@theme` syntax in Context7. Utilities must read the CSS variables, so the themes switch without re-rendering.
- Type styles become small utility classes (`.t-display-xl`, `.t-heading`, `.t-body`, `.t-eyebrow`, `.t-data`, `.t-meta`, …), defined once in `globals.css`.
- **No raw colours:** a lint rule or a test fails on hex, `rgb(` or `hsl(` in `components/` and `app/` outside `app/styles/`.

### 4.2 Fonts

`next/font/google` (or the `geist` package if Context7 shows it's the recommended way for this Next.js version): Geist weights 300, 400, 500 and 600; Geist Mono weights 400 and 500. `display: swap`. Expose them as CSS variables.

### 4.3 Theme

- **Order:** the saved choice (`ms-theme`) if it exists, otherwise the device setting (`prefers-color-scheme`), otherwise dark.
- **Before paint:** an inline script in `<head>` with the CSP nonce reads the order above and sets `document.documentElement.dataset.theme`. Wrap the `localStorage` read in try/catch. If it fails, use the device setting.
- **Toggle:** `ThemeToggle` writes `ms-theme`, sets `data-theme`, and fades background and text over 0.3 s (no fade under reduced motion).
- **Device changes:** if nothing is saved, follow `prefers-color-scheme` changes live.
- `<html suppressHydrationWarning>` only for the `data-theme` attribute.
- `color-scheme` follows the theme, so form controls and scrollbars match.

---

## 5. Design-system components (`components/ds/`)

Port all 19 components from `docs/design-system/components/bundle.js` and `bundle.css` to TypeScript React with these rules:

- **Names and props:** as in `docs/design-system/components/index.d.ts`. Each component's `.md` file is its usage rule.
- **Styles:** the CSS from `bundle.css` moves into `ds.css` with the same class names (`ms-btn`, `ms-flag`, …), reading the same variables. Don't rewrite the styles into a different system. Fix only what TypeScript or React need.
- **Links:** `Button` with `href` renders `next/link` for internal paths, and a plain `<a>` for external ones.
- **`FlagChip`:** any value other than `LOW`, `NORMAL` or `HIGH` renders as UNKNOWN (a dash), as the contract requires. Give the dash an accessible name: "Flag unknown".
- **`StatusPill`:** keeps `role="status"`.
- **`Tabs`:** add arrow-key navigation and `aria-controls` (the bundle version only has clicks).
- **`Dropzone`:** keep the hidden file input reachable by keyboard, with a visible focus ring on the label.
- **`Helix`:** client-only (F9). The motion stops under reduced motion.
- **`Marquee` and `Helix`:** `aria-hidden`.
- **Focus:** every interactive component shows the 2px `accent` focus ring with a 3px offset.
- **Touch targets:** at least 44px.

**Gallery** (`/dev/components`, F11): every component and variant, in a dark column and a light column side by side. This page is what you compare with the design system.

**Tests:** a small render test per component for its rules: `FlagChip` mapping, `Button` disabled and link modes, `IconButton` requires `label`, `ThemeToggle` label text, `Tabs` roles and keys, `StatusPill` live region, `Checkbox` change.

---

## 6. API client (`lib/api.ts`)

### 6.1 Shape

```ts
type Auth = 'none' | 'guest' | 'auto';   // auto = bearer if signed in, else guest (7c)

request<T>(method, path, opts?: {
  body?: unknown | FormData;
  auth?: Auth;                 // default 'none'
  signal?: AbortSignal;
  timeoutMs?: number;          // default 20000
}): Promise<T>
```

- **Base URL:** `NEXT_PUBLIC_API_URL`, validated in `lib/env.ts` at startup. It must be `https` in production builds.
- **JSON:** sends and reads JSON. `FormData` bodies don't set `Content-Type` (the browser sets the boundary).
- **No cookies:** `credentials: 'omit'`.
- **Request id:** reads `X-Request-Id` from every response and puts it on errors.
- **Timeout:** an `AbortController` per call. A timeout becomes `ApiError` with `code: 'TIMEOUT'` (client-only code).
- **Network failure:** `TypeError` from fetch becomes `ApiError` with `code: 'NETWORK_ERROR'` (client-only), and reports to the backend status (section 8).
- **Problem Details:** non-2xx responses parse `application/problem+json` into `ApiError { status, code, title, detail, requestId, errors?, retryAfterSec? }`.
  - `retryAfterSec` comes from `Retry-After`.
  - A non-JSON error body becomes `code: 'INTERNAL_ERROR'` for 5xx, or `UNKNOWN_ERROR` for anything else, with the status kept.
  - An unknown `code` stays as is. The UI falls back by status (section 6.3).
- **Client-only codes** (`NETWORK_ERROR`, `TIMEOUT`, `UNKNOWN_ERROR`) are listed in `api-error.ts` and never sent to the server. They don't go in the contract.
- **`ApiError`:** `toString()` and `message` contain only status, code and requestId, never the body.
- **Logging:** never `console.log` request bodies, response bodies, or headers. In development, log at most `method path status code requestId`.

### 6.2 Credentials

- `lib/credentials.ts` exports an interface:

  ```ts
  interface CredentialProvider {
    authorization(): Promise<string | null>;               // "Bearer …" or null
    onAuthFailure(e: ApiError): Promise<'retry' | 'fail'>;   // 7c: refresh once
  }
  ```

  7a ships a provider that always returns `null` and `'fail'`. 7c replaces it.
- **`auth: 'guest'`:** calls `ensureGuestSession()` (section 7), sends `X-Session-Token`, and never sends `Authorization`.
- **`auth: 'auto'`:** bearer if the provider returns one. Otherwise it behaves like guest.
- **Never send both credentials.** The contract says bearer wins, but sending both leaks the guest token needlessly.
- **Retry once:** on `TOKEN_EXPIRED` or `TOKEN_INVALID` with a bearer, ask `onAuthFailure`. On `'retry'`, repeat the call once, never more. In 7a this branch is covered by a unit test with a fake provider.

### 6.3 Error handling (`lib/error-messages.ts`)

- One table keyed by `code`. Each entry has a title, a body, and the UI surface: inline, banner, screen, or toast.
- **The wording comes from the mockups** (`system-screens--07…` and the system screens). Where a mockup has no text, use the contract's "suggested UI message".
- **Fallbacks by status:** 400 shows the field errors, 401 and 403 show the matching screens, 404 is not found, 429 is "slow down" with the wait time, 5xx shows the 500 screen with the request id.
- **Request id:** every error shown to the user that came from the server offers it. The 500 screen has a "Copy ID" button.
- **Never** show `title` or `detail` text from the server as the main message. Switch on `code` (root rule).

### 6.4 TanStack Query

- One `QueryClient` per browser tab.
- **Retries:** only `NETWORK_ERROR`, `TIMEOUT` and 5xx, up to 2 times with backoff. Never 4xx.
- `refetchOnWindowFocus: false` by default. Later phases turn it on per query when it helps.
- A global `onError` hook feeds `CAPACITY` into the daily-limit banner (section 8.3). Other errors are handled where they happen, not globally.

---

## 7. Guest session (`lib/guest-session.ts`)

- **Storage:** `sessionStorage` key `ms.guest` = `{ token, expiresAt }`. Never `localStorage`, never cookies (root rule).
- **`ensureGuestSession()`:**
  - Returns the stored token if `expiresAt` is more than 60 seconds away.
  - Otherwise calls `POST /api/sessions` with `auth: 'none'`, stores the result, and returns it.
  - **Single flight:** concurrent callers share one in-flight request.
  - **Lazy:** never called on page load. Only a guest action calls it (7b upload or sample, 7c history).
  - A `429 RATE_LIMITED` response is passed to the caller with `retryAfterSec`.
- **`SESSION_INVALID`:** when a guest call fails with it, the client:
  1. clears `ms.guest`
  2. clears every TanStack Query cache entry under the `['reports']` key
  3. publishes a `guest-session-ended` event
  4. passes the error to the caller

  The page decides what to show. A report or history page shows the "Guest session ended" screen. An upload just creates a new session and retries once.
- **Expiry:** `expiresAt` is shown on guest pages later (7b, 7c), so read it through a small hook, `useGuestSession()`.
- The token never appears in logs, in errors, in URLs, or in the React Query devtools labels.

---

## 8. Backend status, banners and toasts

### 8.1 Wake check (`lib/backend-status.ts`)

States: `CHECKING`, `ONLINE`, `WAKING`, `DOWN`.

- **On first page load:** `GET /actuator/health` with a 4-second timeout.
  - `200` with `status: UP` within 1.5 seconds: `ONLINE`.
  - `200` but slower: `ONLINE`. The banner was already showing, so close it.
  - Timeout, network error, or `503`: `WAKING`. Retry with backoff of 3, 6, 9, 12 seconds, then every 15 seconds.
- **`DOWN`:** after 90 seconds of failed retries. The server-not-responding screen is shown only where a page needs data (from 7b on). In 7a it is reachable through `ServerNotResponding` in the gallery and its e2e test.
- **Network errors from any call** move `ONLINE` back to `WAKING` and restart the checks.
- **Attempt counter:** "Attempt 1 · trying again in 9 s" ticks down every second on the 503 screen. "Retry now" checks immediately and resets the backoff.
- **Header:** the `StatusPill` shows `ONLINE`, or `waking` for `WAKING` and `DOWN`. In `CHECKING` it shows nothing (no flash).
- **Visibility:** pause checks while the tab is hidden (`visibilitychange`). Resume on show.
- Only one checker per tab. It never runs during server rendering.

### 8.2 Waking banner

- A violet bar under the header while `WAKING` (mockup `system-screens--07` and `--10`). Text: "Waking up the server. Medi-Scan runs on free hosting that sleeps when idle, so the first load can be slow. You can start uploading now." Phones get the short version from `--10`.
- **Progress:** the thin progress line under the bar is decorative (`aria-hidden`).
- **Dismiss:** the close button hides it for this tab until the state changes again.
- **Screen readers:** announced once through a polite live region, not on every retry.

### 8.3 Daily limit banner

- An orange bar when any response returns `CAPACITY`. Text from the mockup: "The demo has hit today's AI limit. New reports and chat open again in 6 h 12 min (midnight UTC). Your saved reports still open."
- **Countdown:** to the next midnight UTC, computed from `retryAfterSec` when present, otherwise from the clock. Updates every minute.
- **Clears** at midnight UTC.
- **Storage:** in memory only, per tab.
- **Not closable:** it states a fact that blocks uploads.

### 8.4 Inline notices

`InlineNotice` with tones `warn` (orange, like "This file is too large.") and `info`. It has an icon, a bold-weight first sentence (weight 500, not bold), a body, and optionally the code line in `meta` style ("413 FILE_TOO_LARGE"). It is used from 7b on. 7a builds it, adds it to the gallery, and writes its tests.

### 8.5 Toasts

- **Position:** bottom-right on desktop. Full width at the bottom on phones, above any sticky button.
- **Timing:** they close after 6 seconds unless they hold an action (mockup). They have a close button.
- **Content:** an icon, a title, a body, and an optional action button.
- **Screen readers:** a polite live region.
- **Motion:** none under reduced motion.
- **API:** a `toast.show({ icon, title, body, action? })` helper. 7a uses it for one real case, "Copied" after "Copy ID", and shows all mockup examples in the gallery.

---

## 9. Shell and screens

### 9.1 Shell

- **Desktop header** (900 px and up), per the design-system README:
  - wordmark "MEDI·SCAN" (links home), then nav links
  - spacer, `StatusPill`, the sign-in button (F8), `ThemeToggle`
- **Nav links:** "How it works" (`/#how`), "Samples" (`/#samples`), "Privacy" (`/privacy`), "My reports" (`/reports`). The anchor targets and `/reports` arrive in 7b and 7c. Until then, keep the links but point "My reports" at a page that is honest about it (section 9.4).
- **Phone header** (below 900 px): pinned with the `bar` fill and `blur-bar`. Wordmark, spacer, `StatusPill short`, `ThemeToggle`, and the menu `IconButton`.
  - The menu opens a sheet with large links and a full-width sign-in button (F8: hidden for now).
  - The menu traps focus, closes on Esc and on link click, and returns focus to the button.
- **Skip link:** "Skip to content", first in the tab order.
- **Footer:** "MEDI·SCAN — explains your report, not medical advice." on the left. "Privacy" and "GitHub" on the right. The GitHub URL comes from `NEXT_PUBLIC_REPO_URL`. Hide the link if it's unset.
- **Disclaimer:** a `Disclaimer` component with the exact line "Medi-Scan explains what your report says and is not medical advice." Pages with results use it from 7b. Build it now.
- **Layout:** content max width 1360 px. Gutters are `space-8` on desktop, clamped to `space-4` on phones. No horizontal scroll at 320 px.
- **Metadata:** title template "%s · Medi-Scan", a description, `theme-color` for both themes, `robots` noindex for `/dev/*`.

### 9.2 `SystemScreen` layout

One component used by every system screen (mockups `system-screens--01` to `--06`, and `--08`, `--09` for phones):
- eyebrow, a two-part headline (second half `faint`), a body paragraph, optional extra info lines, and actions
- the big numeral (`404`, `401`, `403`, `500`, `503`) on the right, with the Helix behind it on desktop
- on phones, the numeral moves above the eyebrow (see `--08`)

The numeral is `aria-hidden`. The status meaning is also in the eyebrow text.

### 9.3 The six screens

| Screen | Where it's used | Content and actions (copy from the mockup) |
|---|---|---|
| **404 Page not found** | `app/not-found.tsx` | "This page doesn't exist. / Let's get you back." Actions: Back to home, My reports. |
| **Report not available** | Exported component, used by 7b on `REPORT_NOT_FOUND` | Copy from `--02` and `--09`. Actions: Analyze a report, My reports, Sign in with Google (hidden until 7c). The info lines about 24 h and 30 days, and the new-tab hint. |
| **Guest session ended** | Exported component, used on `guest-session-ended` | Copy from `--03`. Actions: Start again (creates nothing until the user acts; links home), and Sign in (hidden until 7c). |
| **403 Admins only** | Exported component, used by 7d on `FORBIDDEN` | Copy from `--04`. Actions: Back to home, Your account (7c; hidden until then). |
| **500 Something went wrong** | `app/error.tsx`, and the API error fallback | Copy from `--05`. The request id in `data` style with "Copy ID", then Try again (calls `reset()`) and Back to home. When `error.tsx` catches a render error with no request id, hide the request id block. |
| **503 Server not responding** | Exported component, driven by `DOWN` (section 8.1) | Copy from `--06`, the live attempt counter, Retry now, Back to home. |

`global-error.tsx` is a minimal version of the 500 screen without the shell. It must work even if the providers failed.

### 9.4 Pages in 7a

- `/privacy`: the content page from `content-pages--02` and `--04`, with the left section index on desktop. The text is in the mockup.
  - **Fill in the placeholders** with real facts from the repo docs: `[AI PROVIDER]` is "Google's Gemini API", and `[RETENTION PERIOD]` is the stats retention from the plan (usage counts are kept 90 days). If the repo docs say otherwise, use them and tell me.
  - Check every statement against `SECURITY.md` and the plan. Don't state anything the backend doesn't do. Point out any sentence you couldn't confirm.
  - The mockup has a "Sign-in cookies" heading, but there are no cookies. Keep the meaning but title it "Sign-in tokens".
- `/` (temporary): a simple page in the shell, with an eyebrow, the hero headline "Your lab report, / in plain language.", one paragraph, and a link to Privacy. No upload panel, no fake buttons. 7b replaces it with the landing page.
- `/reports` (temporary): not built. It falls through to the 404 page until 7c. That's fine.
- `/dev/components`: the gallery (F11), plus a "System screens" section that renders each screen and each banner and toast in both themes.

---

## 10. Security headers and CSP

Set in the Next.js middleware or proxy layer. Check in Context7 what this Next.js version calls it and how it passes the nonce.

```text
default-src 'self';
script-src 'self' 'nonce-{n}' 'strict-dynamic';
style-src 'self' 'unsafe-inline';
img-src 'self' data: blob:;
font-src 'self';
connect-src 'self' {NEXT_PUBLIC_API_URL};
frame-src 'none';
object-src 'none';
base-uri 'none';
form-action 'self';
frame-ancestors 'none';
upgrade-insecure-requests   (production only)
```

- **Development:** may add what React refresh needs (`'unsafe-eval'` in dev only). Production must not.
- **7c** adds the Google Identity Services origins to `script-src`, `connect-src` and `frame-src`. Don't add them now.
- **Other headers:** `Referrer-Policy: strict-origin-when-cross-origin`, `X-Content-Type-Options: nosniff`, `Permissions-Policy: camera=(self), microphone=(), geolocation=()`, `Strict-Transport-Security` in production.
- **Trade-off:** nonce-based CSP makes pages render per request. Write this in `SECURITY.md` together with F7.

---

## 11. Accessibility and responsiveness

- **Contrast:** only the token pairs from the design system.
- **Keyboard:** everything can be reached by keyboard, in a logical order. Focus is visible everywhere.
- **Landmarks:** `header`, `nav` with a label, `main` with `id` for the skip link, `footer`.
- **Reduced motion:** stops the Helix, marquee, status pulse, theme fade, and toast motion.
- **Text size:** works at 200% zoom with no loss of content.
- **Breakpoints:** desktop layout at 900 px and up, the phone layout below it. Check at 320, 390, 900 and 1440 px.

---

## 12. Tests

**Unit (Vitest + Testing Library + MSW):**
1. **Tokens:** the generated CSS is up to date. No raw colours in components.
2. **API client:** Problem Details parsing (with and without `errors`), `Retry-After`, a non-JSON 5xx, network error, timeout, request id on errors, `credentials: 'omit'`, `FormData` without `Content-Type`.
3. **Credentials:** `auth: 'none'` sends nothing. `guest` sends only `X-Session-Token`. Never both. Retry-once with a fake provider: one retry on `'retry'`, none on `'fail'`, never a second retry.
4. **Guest session:**
   - lazy (no call on import or render)
   - single flight with five concurrent callers making one request
   - reuse until 60 seconds before expiry
   - `SESSION_INVALID` clears storage and the `['reports']` cache, and emits the event
   - `sessionStorage` only (assert `localStorage` untouched)
5. **Backend status:** the state machine with fake timers: online, slow, waking, down, recovery, a network error pushing back to waking, paused when hidden.
6. **Daily limit:** the countdown from `Retry-After`, and clearing at midnight UTC (fake clock).
7. **Error messages:** every code in contract 8.1 and 8.2 has an entry. A test reads the code list from `api-types.ts` so a new code without UI text fails.
8. **Theme:** the order (saved, device, dark), the toggle writes `ms-theme`, and a broken `localStorage` falls back safely.
9. **Components:** section 5.

**End-to-end (Playwright, against `next build && next start`, backend mocked with route interception):**
1. **No theme flash:** with light saved, the first paint is light (check `data-theme` on `DOMContentLoaded`). With a device preference only, it matches.
2. The toggle persists across reload.
3. The 404 page renders for an unknown path, in both themes.
4. A thrown render error shows the 500 screen. "Try again" calls reset.
5. **Health:** slow or failing health shows the waking pill and banner, then "online" when it recovers. After the down threshold, the 503 component counts attempts (gallery route in dev, or a test-only route).
6. A `CAPACITY` response shows the daily limit banner with a countdown.
7. **Mobile menu:** opens, traps focus, Esc closes it, focus returns.
8. The skip link moves focus to `main`.
9. **CSP:** the header is present in production mode. No CSP violations in the console on `/`, `/privacy`, and the 404 page. No request to `fonts.googleapis.com` or `fonts.gstatic.com`.
10. **Accessibility:** `@axe-core/playwright` on `/`, `/privacy`, the 404 page, and the gallery, in both themes. No serious or critical violations.
11. **Layout:** no horizontal scroll at 320 px on every page.
12. **Screenshots:** `/privacy`, the 404 page, and each system screen at 1440 and 390 px, in both themes, saved to `client/e2e/__screenshots__/` (gitignored). These are for the visual review (section 13), not pixel-diff assertions.

---

## 13. Visual review against the mockups

After the build, Claude Code compares its screenshots (12, e2e item 12) with `docs/ui/screens/` for the 7a screens and lists the differences in three groups:
1. **Fixed:** differences it corrected.
2. **Intentional:** differences on purpose, for example the hidden sign-in button.
3. **Open:** differences it couldn't resolve.

Spacing, type sizes, colours, copy and alignment all count. Approximate is fine. Visibly different is not.

---

## 14. Docs to update (first, before code)

1. **`docs/plan.md`:**
   - Section 14: split Phase 7 into 7a to 7d per section 1, and add "Deploy a first version" after 7b.
   - Stack table: icons are the design-system set (no Lucide), fonts are Geist (self-hosted), shadcn/ui only for behaviour primitives.
   - Repository structure: add `docs/ui/` and `docs/design-system/`.
2. **Root `CLAUDE.md`, "Frontend":**
   - Styling comes only from `docs/design-system/` tokens and components. No raw colours.
   - Icons only from the design-system `Icon`.
   - Flags are never red.
   - Every page with results carries the disclaimer line.
   - The theme exception: `ms-theme` in `localStorage` is allowed. It is not a token, and tokens are still never in `localStorage`.
   - Under "Design docs", add `docs/design-system/README.md` and `docs/ui/README.md`.
3. **`docs/dataflow.md`:** in the guest session section, the client creates the session lazily and clears report caches on `SESSION_INVALID`. Check that this already says so; add it only if missing.
4. **`SECURITY.md`:** the CSP (section 10), the reasons for F7 and the nonce trade-off, and the theme storage note.
5. **`docs/api-contract.md`:** no change. Client-only error codes stay out of the contract.
6. **`.gitignore`:** `client/e2e/__screenshots__/`, and Playwright's report folders.

Edit what is in the repo now. The repo copies may differ from this list.

---

## 15. Done checklist

1. `npm run lint`, `npm run typecheck`, `npm test`, and `npm run build` pass in `client/`.
2. `npm run e2e` passes (Playwright, production build).
3. No serious or critical axe violations, in both themes.
4. No CSP violations and no Google Fonts requests.
5. The gallery shows all 19 components, the banners, the toasts and the system screens, in both themes.
6. The visual review list exists, and the "Open" group is short and explained.
7. No backend changes. `backend/CLAUDE.md` is untouched in git (it stays ignored).

---

## 16. Open items for the final report

- Next.js, React, Tailwind, TanStack Query, shadcn, Playwright and Vitest versions actually used.
- How the nonce reaches the theme script and Next's own scripts in this Next.js version.
- Anything in the design system that didn't port cleanly to React or Tailwind.
- Privacy page statements that couldn't be confirmed.
- The visual review list.
