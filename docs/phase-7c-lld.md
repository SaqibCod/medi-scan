# Phase 7c: Sign-in, My reports, Account, Trends (low-level design)

Frontend only (`client/`). The backend for all of this exists from Phases 6a and 6b. Branch: `feat/phase-7c-accounts-ui`, created from `dev`.

This phase adds:
- **Google sign-in** in the browser: tokens, refresh, auto sign-in in new tabs, and logout in every tab
- **My reports** (`/reports`), for guests and signed-in users
- **Account** (`/account`): profile, sign out, delete account
- **Trends** (`/trends`): charts across reports
- turning on every sign-in action that 7a and 7b hid

**Overall order (unchanged):** 7a → 7b → Phase 4 → Phase 5 → **7c** → 7d → Phase 8 (deploy).

**References**
- `docs/api-contract.md`: 2.1 to 2.7 (auth and client token rules), 4.5 (history), 5 (trends), 8, 9.
- `docs/dataflow.md`: 3.1 to 3.6 (identity flows), 7 (history and trends).
- Root `CLAUDE.md`, "Frontend": token storage, refresh handling, auth state, new tabs, logout, XSS.
- `docs/ui/screens/`: `my-reports-page--*`, `account-page--*`, `trends-page--*`, `system-screens--07` and `--10` (sign-in toasts).
- `docs/phase-7a-lld.md` 6.2 (the `CredentialProvider` slot), `docs/phase-7b-lld.md` B10, `docs/phase-5-lld.md` 10 (chat state).

---

## 1. Decisions (already made, confirm at the approval point)

| # | Decision | Reason |
|---|---|---|
| A1 | **Use Google's own rendered button** (`google.accounts.id.renderButton`, `shape: "pill"`, `theme` following our theme, `text: "signin_with"`). It goes everywhere the mockups show "Sign in with Google", with our pill as the layout slot only. | The ID-token flow can't be started from a custom button. Google's branding rules require its button. The design-system README already says to follow them. |
| A2 | **Auto sign-in on tab start:** a tab with no refresh token calls `google.accounts.id.prompt()` once, with `auto_select: true` (FedCM when the browser supports it, checked in Context7). Returning users are signed in without a click. Others may see Google's One Tap prompt, or nothing, and stay guests. | Contract 2.1 and dataflow 3.3. Google's own cool-down handles people who dismiss it. |
| A3 | **Token storage, exactly as the rules say:** the access token in memory. The refresh token in `sessionStorage` under `ms.refresh`. The user object (id, name, role) in memory, refetched from the token response or `/api/me` on reload. Nothing in `localStorage` or cookies. | Root `CLAUDE.md`. |
| A4 | **Duplicated-tab guard:** "Duplicate tab" copies `sessionStorage`, so two tabs would share one refresh token and trip reuse detection. On start, each tab announces its tab id on a `BroadcastChannel`. If another live tab already owns that id, this tab is a copy: it **drops `ms.refresh`** and signs in again through A2. Guest tokens can be shared safely, so `ms.guest` is kept. | Without this, a duplicated tab logs the user out everywhere. |
| A5 | **Proactive refresh:** refresh once, 60 seconds before `accessTokenExpiresAt`, single-flight per tab. Also refresh once on `401 TOKEN_EXPIRED` or `TOKEN_INVALID`, then retry the call once. | Fewer failed calls. The rules forbid loops and parallel refreshes. |
| A6 | **My reports for guests too** (6b D1), with the guest banner from `my-reports-page--02`. | Matches the backend and the mockup. |
| A7 | **Status tab counts** ("Done 11, Processing 1, Failed 2") come from four `GET /api/reports?status=…&size=1` calls, reading `totalItems`, cached for 30 s and refetched after a delete. No backend change. | The contract has no counts endpoint. Four tiny calls are fine at this scale. |
| A8 | **Page size 6**, numbered pages (mockup). | Matches the design. The contract allows 1 to 50. |
| A9 | **Charts with Recharts** (already in the plan's stack). Each chart has a **Table** view with the same data, as the accessible alternative. | Plan, and accessibility. |
| A10 | **Chat during sign-in** (agreed after Phase 5): the open conversation stays in memory. Sending is paused while sign-in is in progress, and a running answer finishes first. After sign-in, the report keeps its id (claimed), so chat continues with the bearer token. A reload loses the chat, as "Not saved" already says. | Agreed decision. |
| A11 | **No Admin link** in this phase, even for admins. It comes in 7d. | Scope. |

---

## 2. Auth in the browser

### 2.1 State (`lib/auth/`)

```ts
type AuthState =
  | { status: 'RESOLVING' }
  | { status: 'GUEST' }
  | { status: 'SIGNED_IN'; user: User; accessExpiresAt: number }
  | { status: 'SIGNING_IN' };      // Google callback received, server call in flight
```

- An `AuthProvider` with `useAuth()` exposes state, `signInWith(idToken)`, `signOut()`, and `deleteAccount()`.
- **Memory only:** the access token lives in a module-level variable inside `lib/auth/tokens.ts`. It is never in React state, never in devtools labels, and never logged.
- **Plugging in:** `CredentialProvider` (7a 6.2) gets its real implementation. `authorization()` returns `Bearer …` when signed in. `onAuthFailure()` runs the A5 refresh and returns `'retry'` or `'fail'`.
- **Callers:** every report, chat and history call switches from `auth: 'guest'` to `auth: 'auto'`.

### 2.2 Tab start (dataflow 3.3)

1. **Duplicated-tab check** (A4), at most 150 ms.
2. **If `ms.refresh` exists:** `POST /api/auth/refresh`.
   - **Success:** `SIGNED_IN`.
   - **`REFRESH_TOKEN_INVALID`:** clear the tokens, then go to step 3.
   - **Network error:** stay `RESOLVING`, and retry with the 7a status logic. Never treat a network error as signed out.
3. **No refresh token:** `GUEST`, then load the Google script and call `prompt()` once (A2). If Google returns a credential, run 2.3.
4. **Waiting pages:** report pages, My reports, Account and Trends wait while `RESOLVING`, or while a prompt is pending, **for at most 2 seconds** (contract 2.1). After that they render with whatever state exists. A late sign-in re-runs their queries.

**Correction, verified against Google's docs during build.** Steps 3 and 4 cannot be driven by Google's `prompt()` notifications. `use_fedcm_for_prompt` is deprecated and ignored — FedCM is simply on — and under FedCM `isNotDisplayed()` and `getNotDisplayedReason()` report nothing, `getSkippedReason()` is gone, and even the display moment is deliberately delayed by up to a minute. There is therefore no signal that says "no auto sign-in is coming".

So the 2-second budget is **the tab's own timer**, and nothing waits on a notification. A credential is accepted whenever it arrives, however late, and the invalidation in 2.3 is what makes a late one safe: the pages that had already rendered as a guest re-run their queries. The moment listener is still passed to `prompt()`, but only so GIS has somewhere to deliver notifications, and its argument is ignored.

### 2.3 Signing in (dataflow 3.2)

1. Google gives an ID token through the callback (from the button or the prompt). The state becomes `SIGNING_IN`.
2. `POST /api/auth/google` with `{ idToken, guestSessionToken }`. Send `ms.guest` if present, so the guest reports are claimed.
3. **On success:**
   - store the tokens (A3), and remove `ms.guest` (its reports now belong to the user)
   - invalidate every `['reports', …]` and `['trends', …]` query
   - the state becomes `SIGNED_IN`
   - **toasts** (mockup copy):
     - `newUser`: "Welcome to Medi-Scan, {name}." / "Your {n} guest reports moved to your account. They are now kept for 30 days." (the second line only when `claimedReportCount > 0`; "report" when n = 1)
     - returning user: "Signed in as {name}." / "{n} report from this tab moved to your account." (only when n > 0)
4. **Errors:**
   - `GOOGLE_TOKEN_INVALID`: toast "Sign-in didn't work. Please try again." Stay `GUEST`.
   - `RATE_LIMITED`: toast with the wait time.
   - Network error: toast and stay `GUEST`.
   - The ID token is never stored or logged. Drop it right after the call.

### 2.4 Refresh (A5)

- A single in-flight promise per tab. Concurrent callers await it (root rule).
- **Success:** replace both tokens.
- **`REFRESH_TOKEN_INVALID`:** clear the tokens, set `GUEST`, call `google.accounts.id.disableAutoSelect()`, and show the toast "You were signed out." / "Your sign-in ended. Sign in again to see your saved reports." with a sign-in action (mockup).
- **Retries:** never refresh twice for one failed call. Never retry a call twice.

### 2.5 Sign out everywhere (dataflow 3.5)

- **`signOut()`** in the tab that starts it:
  - `POST /api/auth/logout` with its refresh token (a 204 even on failure, contract)
  - clear its own tokens, call `disableAutoSelect()`
  - post `"logout"` on the `ms-auth` `BroadcastChannel` (no tokens in the message)
  - go to `/`, and show the toast "Signed out."
- **Other tabs**, on `"logout"`: each one logs out its **own** refresh token, clears it, calls `disableAutoSelect()`, and becomes `GUEST`. A tab on Account or Trends shows the not-signed-in state.
- **Guest token:** sign-out leaves `ms.guest` untouched. It was already removed at sign-in, so a new guest session starts lazily.

### 2.6 Google script and CSP

- **Loading the script:** load `https://accounts.google.com/gsi/client` with the request nonce (7a CSP), only on the client, once.
- **CSP additions:** the exact origins Google documents for GIS with FedCM and the button, checked in Context7 and Google's docs. **Three directives, not four:**
  - `frame-src https://accounts.google.com/gsi/`
  - `connect-src https://accounts.google.com/gsi/`
  - `style-src https://accounts.google.com/gsi/style`

  Nothing broader, and each one a path under a single origin rather than a wildcard. Google asks for the *parent* path rather than individual endpoints so that a change on their side does not break sign-in here.

  **`script-src` is deliberately left alone**, though Google's documentation lists `script-src https://accounts.google.com/gsi/client`. Our policy already carries `'strict-dynamic'`, which makes every host expression in that directive inert — adding the entry would change nothing and would only suggest it was doing something. The loader carries the request nonce instead, and `'strict-dynamic'` extends that trust to everything GIS goes on to load.
- **Cross-Origin-Opener-Policy: no change needed, confirmed.** 7a set no COOP header at all, and Google's docs are explicit that with FedCM enabled the browser renders prompts directly and no COOP change is required. COOP matters only for the popup `ux_mode` flows (`initTokenClient` / `initCodeClient`), which this app does not use. `X-Frame-Options: DENY` and `frame-ancestors 'none'` govern *our* page being framed by someone else and have no bearing on us framing Google.
- **Client ID:** `NEXT_PUBLIC_GOOGLE_CLIENT_ID`. If it's unset, sign-in UI is hidden (the 7a flag logic) and a single console warning appears in development only.
- **Flag:** turn on `NEXT_PUBLIC_FEATURE_SIGNIN=true` in `.env.example`, and remove the 7a/7b hidden states that it controlled.

### 2.7 Chat during sign-in (A10)

- While the state is `SIGNING_IN` or a refresh is in flight, the chat input is disabled, with the placeholder "Signing you in…".
- A stream that is already running keeps using the credential it started with, and finishes.
- After `SIGNED_IN`, the next question uses the bearer token automatically (`auth: 'auto'`).
- If the claim didn't include this report (for example, it belonged to another tab's session), the next chat call returns `REPORT_NOT_FOUND`. Show the 7a "Report not available" screen.

---

## 3. Header and shell changes

- **Signed in** (`my-reports-page--01`):
  - nav links: How it works, Samples, My reports, Trends
  - an avatar pill (initial in a circle, then the display name) links to `/account`, in place of the sign-in button
- **Guest:** nav links How it works, Samples, My reports. The Google button (A1) sits in the sign-in slot.
- **Resolving:** the slot shows a neutral skeleton pill the same size, so there's no layout jump.
- **Phone menu sheet:** the same links. The full-width Google button for guests, or the account row for users.
- **Unhidden now:**
  - "Sign in to keep it" (report guest note)
  - sign-in on "Report not available" and "Guest session ended"
  - "Your account" on the 403 screen
  - "See trend" on biomarker pages (signed in only; links to `/trends?marker={slug}`)

---

## 4. My reports (`/reports`)

Match `my-reports-page--01` to `--07`.

**Header:**
- Eyebrow "My reports". Headline "Your reports." Second line:
  - user: "{n} saved, each kept for 30 days."
  - guest: "{n} in this session, gone in 24 hours."
  - `n` is the "All" count.
- "New report" (solid) goes to `/#upload`.

**Guest banner** (`--02`): the mockup text plus the Google button. Signed-in users don't see it.

**Trends teaser** (signed in, only when `/api/trends/markers` returns at least one marker; `--01`):
- "{k} markers appear in more than one report. / See how they've changed."
- Up to two marker cards, each with a sparkline, "{reportCount} reports · {first} → {last} {unit}", linking to `/trends?marker=…`.
- The first and last values come from `GET /api/trends?marker=` for those two markers, using the **first series** only (the most points).

**Filters:** tabs All, Done, Processing, Failed with counts (A7). Processing covers `PENDING` and `PROCESSING`. The API filter takes one status, so the Processing tab makes two `size=1` calls and adds them up, and lists with two pages merged. Or show `PROCESSING` only and say so: **pick the simpler correct option at approval point 2.**

**List rows** (`ReportListItem`):
- **Date block:** `collectedOn` with "Collected", otherwise the `createdAt` date with "Uploaded".
- **Title:**
  - `DONE`: "Lab report", or "Sample report" for `SAMPLE`
  - processing: "New report"
  - `FAILED`: "Report could not be read"
- **Tag:** `PDF`, `Photo` (`IMAGE`), `Pasted text`, or `Sample`.
- **Subline:** "Uploaded {date, time}".
- **Middle column:**
  - `DONE`: a flag bar with segments in the high, normal and low token colours, proportional to the counts, labelled "{total} tests · {h} high · {l} low · {n} normal" (zero parts omitted)
  - processing: an indeterminate bar with "Reading your report…"
  - `FAILED`: the short failure text for its `errorCode` from `lib/copy.ts`
- **Right:**
  - signed in: "Expires in {n} days"
  - guest: "Deleted in {n} h", in the warning colour
  - then an arrow link to `/reports/{id}` and a delete `IconButton` (with the 7b dialog)

**Bottom:** "Showing {a}–{b} of {total}", numbered pagination with chevrons, and the footer line ("Reports are deleted automatically 30 days after upload. Chats are never saved." or the guest version).

**States:**
- empty (`--03`, `--06`): per mockup
- loading: skeleton rows
- errors: through the 7a screens, with `SESSION_INVALID` for a guest who has no session meaning an **empty list**, not an error. The guest token is lazy, so a guest with no `ms.guest` doesn't call the API and sees the empty state.

**Polling:** if any visible row is processing, refetch the page every 3 s until none are.

**Pages:** `?page=` and `?status=` live in the URL, so back and forward work.

---

## 5. Account (`/account`)

Match `account-page--01` to `--06`.

- **Signed in:**
  - **Profile card:** initial, name, "Google account" tag, "Member since {createdAt}", "Reports saved {reportCount}" (from `GET /api/me`).
  - **Sign out:** an outline button with the note "Signs you out in every open tab of this browser." (2.5).
  - **Stat cards:** "{reportCount} My reports, each kept for 30 days" links to `/reports`. "{k} Markers you can follow on Trends" links to `/trends` (k from `/api/trends/markers`).
  - **"What Medi-Scan keeps about you":** the four rows from the mockup, unchanged. Check each against `SECURITY.md` and tell me if anything is wrong.
  - **Delete account** (`--02`, `--05`): the card opens an inline confirm.
    - Text: "Delete your account and all {n} of your reports for good?"
    - A required checkbox: "I understand my reports can't be recovered and I'll be signed out everywhere."
    - Buttons: "Keep my account" and "Delete my account" (disabled until the box is ticked).
    - On confirm: `DELETE /api/me`, then the same as sign-out everywhere (2.5) without the logout call, and the toast "Account deleted." / "Your reports, results and sign-ins are gone from our server."
- **Not signed in** (`--03`, `--06`): the sign-in card with the three benefit lines, the Google button, "Continue as guest" (to `/`), and the note about guest reports moving on sign-in.
- **Expired token on load:** handled by A5. If the refresh fails, show the not-signed-in state.

---

## 6. Trends (`/trends`)

Match `trends-page--01` to `--05`.

- **Not signed in** (`--04`): the not-signed-in card, as on Account, with Trends wording from the mockup.
- **Not enough data** (`--03`, when `/api/trends/markers` is empty):
  - "Nothing to compare yet." with "You have {n} finished report(s) so far."
  - `n` comes from `GET /api/reports?status=DONE&size=1`
  - actions "Add a report" and "My reports"
- **With markers:**
  - **Left list:** "In 2 or more reports", the markers with "{reportCount} reports", and the note "A test shows up here once it appears in at least two finished reports."
  - **Selected marker:** `?marker=` in the URL, defaulting to the first. A marker in the URL that isn't in the list still loads (it may have a single point). An empty `series` shows the not-enough state for that marker.
  - **Title** `displayName`, and a **Chart / Table** toggle (Tabs).
  - **One card per series** (unit). With more than one unit, show the notice from `--02`: "{name} was reported in {k} different units. Medi-Scan never converts between units, so each one gets its own chart."
- **Card header:**
  - "In {UNIT}", then the latest value, unit, and flag chip
  - on the right: "{+/-delta} {unit} since {first date}" and "{count} reports"
  - one point only: "Latest from {date}"
  - the delta is computed from the first and last points of that series only, and shown with its sign
- **Chart (Recharts):**
  - x = date. y = value, with automatic nice ticks and padding.
  - A line in `accent`. Dots filled in the flag colour.
  - **Hollow dots** for `dateSource: UPLOADED`, with the legend "Hollow dot: dated by upload".
  - **Reference band:** from the **latest point's** `refLow` and `refHigh`, a shaded `flag-normal-bg` area with dashed edges and the label "Range {text}", legend "Reference range on your latest report". If only one bound exists, shade from it to the axis edge. If none, no band.
  - **One point:** no line, and the note "Only one value in {unit} so far. A line appears once there are two."
  - Values are labelled on the latest point. The tooltip shows date, value, unit, flag, and "dated by upload" when relevant.
  - **Tokens only:** colours come from tokens through CSS variables, so the theme switch works. Charts respect reduced motion (no animation).
- **Table view:**
  - columns Date, Value, Range (each point's own `refLow`–`refHigh`, or "—"), Flag, and Report (a link)
  - newest first
  - this is the accessible view, and the chart has an `aria-label` that points to it
- **Footer note:** "The shaded band is the range printed on your most recent report. Labs set their own ranges, so the table shows each report's own. Trends show what your reports say over time and are not medical advice."
- **Cap:** the API returns at most 100 points per series (6b). Nothing more to do.

---

## 7. Copy and medical safety

- All new strings live in `lib/copy.ts`, taken from the mockups.
- **Trends only show numbers that are in the data.** No ideal values, no targets, no "improving" or "worsening" words. A delta is a plain signed difference.
- The Google display name is shown as text (React escapes it). It is never placed in titles beyond "Your account", and never logged.

---

## 8. Privacy and security checks

- **Storage:** `localStorage` holds only `ms-theme`. `sessionStorage` holds only `ms.guest` and/or `ms.refresh` (and the A4 tab id, if it uses `sessionStorage`). No ID token, access token or user data in storage.
- **No token** in URLs, logs, the console, errors, React Query keys or devtools labels.
- **BroadcastChannel messages** carry no tokens and no user data (`"logout"`, and the A4 tab-id handshake only).
- **CSP:** only the GIS origins are added (2.6). Still no analytics.
- **Data:** `SECURITY.md` gets a short "Sign-in in the browser" section: what is stored where, A4, and the CSP additions.

---

## 9. Docs to update (first, before code)

1. **`docs/plan.md`:** section 14 shows 7c as current. Add a note that Google's rendered button is used (A1).
2. **`docs/dataflow.md` 3.3 and 3.5:** add the duplicated-tab guard (A4) and the proactive refresh (A5) if missing.
3. **Root `CLAUDE.md`, "Frontend":**
   - "Use Google's rendered sign-in button, never a custom one."
   - "Duplicated tabs must drop the copied refresh token (A4)."
4. **`SECURITY.md`:** section 8.
5. **`docs/api-contract.md`:** no change. If anything doesn't match the backend, stop and tell me.

Edit what is in the repo now.

---

## 10. Tests

**Unit (Vitest, MSW, fake timers, a fake `google.accounts.id`):**
1. **Tab start:**
   - with a refresh token: refresh, then `SIGNED_IN`
   - with an invalid refresh token: cleared, then `GUEST`, then prompt
   - network error: stays `RESOLVING`, then retries
   - no token: prompt, and a credential leads to sign-in
2. **Sign-in:**
   - the request includes `guestSessionToken`
   - success stores tokens, removes `ms.guest`, invalidates queries, and shows the right toast for each combination of `newUser` and `claimedReportCount`
   - errors
   - the ID token is never stored
3. **Refresh:**
   - proactive timing
   - 5 concurrent 401s cause exactly one refresh and 5 retries
   - no second refresh for the same call
   - `REFRESH_TOKEN_INVALID` leads to signed out, `disableAutoSelect`, and the toast
4. **Logout everywhere:**
   - the initiating tab and a second tab (two `BroadcastChannel` instances in the test) each log out their own token
   - messages carry no token
5. **Duplicated tab (A4):**
   - the copy drops `ms.refresh` and keeps `ms.guest`
   - the original tab is unaffected
   - a single tab is never treated as a duplicate
6. **Waiting pages:** they wait at most 2 s while `RESOLVING`, and re-query after a late sign-in.
7. **Chat during sign-in (A10):** the input is disabled while signing in, a running stream completes, and the next question uses the bearer token.
8. **My reports:**
   - row mapping for every status, source type and count combination
   - the flag bar proportions
   - expiry text for guest and user
   - tab counts (A7)
   - pagination and URL state
   - polling while processing
   - a guest with no session sees the empty state and makes no API call
9. **Trends:**
   - series mapping
   - delta and sign
   - single point
   - hollow dots
   - the band from the latest point with one bound, both, or none
   - the multi-unit notice
   - the table view rows
   - no conversion between units
10. **Account:** the delete confirm gating, and success clearing everything.

**End-to-end (Playwright, production build, backend mocked, GIS replaced by a test stub served through route interception):**
1. Guest uploads a sample, signs in from the report's guest note, sees the welcome toast, and the report is now in My reports with "Expires in 30 days".
2. Reload keeps the user signed in (refresh). Opening a new tab signs in through the auto prompt stub.
3. Duplicated tab (simulate by copying `sessionStorage` into a new page): no reuse error, both tabs stay signed in.
4. Sign out in one of two tabs: both become guests.
5. Delete account: everything clears, and `/account` shows the not-signed-in state.
6. Trends: select markers, Chart/Table toggle, two-unit case, single-point case, not-enough case, guest case.
7. My reports: filters, pagination, delete from the list, empty state, guest banner.
8. Chat continues after signing in mid-conversation.
9. axe on `/reports`, `/account`, and `/trends` (chart and table) in both themes. No CSP violations. No requests to origins other than the site, the API, and Google's GIS origins.
10. Screenshots at 1440 and 390 px in both themes for the visual review.

**Manual check with real Google** (local backend from 6a with `GOOGLE_CLIENT_ID` set):
- sign in with my account, check the claim, reload, a new tab, a duplicated tab, sign out everywhere, then sign in again and delete a test account
- I'll do the Google steps when you ask

---

## 11. Done checklist

1. Docs first. `lint`, `typecheck`, `test`, `build`, and `e2e` pass.
2. **Real Google run** (manual, with me) works for every item in the manual check.
3. **Storage, CSP, and request-origin checks** pass (section 8).
4. **Visual review** against the 7c mockups: fixed, intentional, and open lists.
5. **No backend changes.** `backend/CLAUDE.md` is untouched in git.

---

## 12. Open items for the final report

- The GIS and FedCM behaviour seen in Chrome, Firefox and Safari: auto sign-in, One Tap, and the button.
- The exact CSP origins added, and whether COOP needed a change.
- The Processing-tab choice (section 4).
- Recharts version, and anything in the chart design that didn't fit it.
- Anything to decide before 7d (admin).