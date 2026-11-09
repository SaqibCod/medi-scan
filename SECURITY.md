# Security and Privacy

Medi-Scan is a portfolio demo. It is **not** a medical device, not a clinical
tool, and makes no compliance claim of any kind — HIPAA, GDPR, or otherwise.

## Synthetic data only

**Please don't upload a real medical report.** Use the bundled sample reports, or
a report you have made up.

This isn't a formality. Two reasons:

1. **Masking is best-effort, not a guarantee.** See the limits below.
2. **The default model provider is a free tier.** Free-tier LLM terms generally
   allow the provider to use submitted content to improve their models. Text sent
   to the model is masked first, but masking can miss things, so anything you
   upload should be data you would be comfortable publishing.

The upload form carries a consent checkbox that repeats this.

## What masking does, and where it stops

Before any text reaches a model or the database, it goes through four stages.

**1. Result rows are identified and protected.** A line counts as a lab result
when it holds a number *and* at least one of: a unit, a reference range, or a
trailing `H`/`L` flag. The whole line is then untouchable — test name, value,
unit and range. This happens *first*, because everything after it is a heuristic
over text full of digits, and a phone-number pattern will happily match part of a
result row given the chance.

Requiring that second signal is deliberate. `Patient ID: 12345` has a number and
nothing else, so it stays maskable — if ID lines were protected, no MRN would ever
be masked.

**2. Label-anchored rules run, highest precision first:** social security
numbers, email addresses, labelled identifiers (`MRN`, `Patient ID`, `Accession`,
`Specimen ID`, …), birth dates, phone numbers, labelled names (`Patient Name:`,
`Ordered by:`, `Physician:`, …), and names after an honorific (`Dr.`).

Two of those are narrow on purpose:

- **Dates are only masked next to a birth-date label.** A bare date is never
  touched, so the specimen collection date survives — losing it would silently
  drop the report from every trend chart.
- **Identifiers are only masked next to an identifier label.** A bare
  alphanumeric token is far more often a lab value than an id.

**3. An optional OpenNLP name finder**, for names with no label at all. **It is
off by default** (`MASK_OPENNLP_ENABLED=false`) — see the measurements below.

**4. A value integrity check.** The numeric tokens of every protected result row
are compared before and after masking. If any differ, the report fails rather
than being stored. Stages 1–3 are best-effort; this one is a hard guarantee.
Corrupting a lab value would put a wrong number in front of a patient, which is
worse than leaving a borderline match unmasked.

Matches are replaced with typed placeholders such as `[NAME]` and `[PHONE]`
rather than deleted, so the line keeps its shape for the extraction step and a
reader can see what was removed.

### Measured on the bundled samples

Against the three sample reports, each carrying a planted name, physician name,
MRN, accession number, specimen id, birth date and two phone numbers:

| | Label rules only (default) | With OpenNLP enabled |
|---|---|---|
| Planted personal data masked | all of it | all of it |
| Spans masked per report | 8 | 8 |
| Masking conflicts | 0 | 0 |
| Lab values or ranges altered | 0 | 0 |
| Collection dates lost | 0 | 0 |

**The OpenNLP model contributed nothing.** On these reports it found no name the
label rules had not already caught, because lab reports label their people. On
its own it found 1 of the 6 planted names.

It did produce false positives, which is why its filters exist and why it stays
off:

- It proposed `Jane Q. Roe        Accession` as a single name — running across a
  column gap into the next field's label. The model works on tokens and cannot see
  the layout. Now rejected by a filter on runs of two or more spaces.
- It proposed `O.B.`, pulled out of a `D.O.B.:` label, as a person. Now rejected
  by a filter requiring at least one word of two or more letters.

With both filters in place it proposes no false positives on these samples — but
it also adds no value on them, so the label rules are what the project relies on.

### Known limits

These are measured, not estimated. The numbers come from the Phase 3 evaluation
set — 31 synthetic reports with every planted identifier written down in advance,
then checked against the masked text, the model prompts, and every text column in
the database. Method and full results: `docs/evaluation.md`.

**What the measurement found** (2026-10-20, 31 cases):

| Type | Result |
|---|---|
| `ID`, `PHONE`, `EMAIL`, `SSN`, `DOB` | no leaks |
| `NAME` | 2 leaks, both the "no label, no honorific" case below |
| `ADDRESS` | **not masked at all** — no address rule exists |

No lab value, unit or reference range was damaged by masking in any case, so the
rules are not buying their coverage by over-masking the results.

Masking will miss:

- **addresses, entirely.** There is no address rule, so a street address in a
  header or footer reaches the model and the database. This is the largest known
  gap and the one to close first.
- **a second mention of a name in free text** — `Note: Jane Roe was seen for a
  routine follow-up.` The labelled occurrence in the header is masked; a later
  mention in prose has no label to key on. Confirmed in the evaluation.
- **names behind a label the list does not know, or with no colon** — the
  labelled-name rule requires `Label: Name`, which is how lab reports punctuate
  headers. `Reviewed by Victor Hammond.` is missed on both counts. Confirmed in
  the evaluation.
- **names in a column layout with no colon** — a space-aligned
  `Patient Name    Jane Roe` is missed, for the same reason.
- names it cannot recognise as names: unusual spellings, non-Latin scripts, names
  that are also common words
- personal details written into other free-text comment fields and physician notes
- identifiers in formats the label list does not anticipate
- text that OCR mangled badly enough to break the patterns

The statistical person-name finder (OpenNLP) that could catch free-text names is
present but **off by default**: on the samples tested it added no value the label
rules did not already cover, and a name finder trained on news text carries its
own risk of masking a lab term. The figures above therefore describe the label
rules alone.

**Read these numbers for what they are.** 31 invented reports are not a claim
about real lab paperwork, which brings layouts, abbreviations and identifier
shapes this set does not contain. Masking is best-effort, and the limits above
are the reason the advice at the top of this file — do not upload a real report
you would not want to lose control of — is not boilerplate.

The test suite checks masking in **both** directions — that personal data is
removed, *and* that every lab value and reference range survives byte for byte,
column spacing included. A separate canary test runs a unique fake name and id
through the whole pipeline and asserts they appear in no log line, no database
column, no model prompt, and no response body.

## Reading photos and scans (OCR)

A photo of a report, or a PDF whose pages are images, is read with Tesseract
before anything else happens to it. Three things about that are worth stating
plainly.

**The image never reaches a model.** Only the OCR text does, and only after
masking, exactly like text pulled from a PDF. No model in this app sees a
picture.

**OCR runs outside the server process.** Tesseract is the `tesseract`
command-line program, started as a child process — not a native library loaded
into the JVM. The difference matters: a crash in native code inside the server
takes the whole server down, while a child process can be killed and its memory
comes back when it exits. The specifics:

- **No shell.** Arguments are passed as a list, so nothing in a file name or a
  path can be read as a command.
- **A timeout per page and a budget per report.** A page that overruns is killed
  (`destroyForcibly`), and the report fails rather than hanging.
- **Capped output.** The program's stdout is read on a separate thread with a
  byte limit; past it, the process is killed. Its stderr is drained into a small
  bounded buffer so a full pipe can't deadlock it, and is never logged.
- **One page at a time,** under a single permit. The production box has 2 GB of
  RAM, and OCR is the most memory-hungry step in the pipeline.
- **An image size limit.** The pixel dimensions are read from the file header
  *before* anything is decoded, and an image over 40 megapixels is refused as
  unreadable. A 2 KB file can legitimately claim to be 50,000 × 50,000 pixels;
  decoding it first to find out is how a decompression bomb wins.
- **A page limit.** At most 10 pages per report go through OCR.
- **Nothing from OCR is logged** — not the text, not a word of it, not the file
  name, not Tesseract's stderr. A page logs its exit code, how long it took, how
  many words it found, and their mean confidence.

**A value OCR wasn't sure about is never flagged.** This is the one place where
OCR changes what you see. OCR misreads digits — `5.4` becomes `54`, `0.8`
becomes `O.8`, a decimal point goes missing — and a wrong number with a
confident `HIGH` next to it is the worst thing this app could show anyone. So
Tesseract is asked for a confidence score for every word it reads, and each
value is checked against the confidence of the characters it actually came from:

- Below the threshold, the value keeps the text as read, but gets no flag
  (`UNKNOWN`) and no numeric value, and is left out of trend charts.
- The same applies to a reference range read with low confidence, and to the
  collection date.
- The results page says the text came from a photo or scan and asks you to check
  each value against the paper report.

The cost of that rule is that some perfectly good values come back marked
unclear. That is the intended trade: unclear is recoverable by looking at your
report, a confidently wrong flag is not.

**Measured on the evaluation set** (Phase 4, OCR cases rendered from the same 31
synthetic reports — method in `docs/evaluation.md`):

| | Result |
|---|---|
| Dangerous flag errors on OCR cases | _filled in from the Phase 4 run_ |
| Values marked unclear by the confidence rule | _filled in from the Phase 4 run_ |
| Value accuracy on matched rows | _filled in from the Phase 4 run_ |
| Personal-data leaks on OCR cases | _filled in from the Phase 4 run_ |
| Confidence threshold in use | _filled in from the Phase 4 run_ |

OCR also gives masking a harder job: it can garble a name badly enough that the
patterns that would have caught it no longer match, which is the case already
listed under the masking limits above. The evaluation measures leaks on OCR
cases separately for that reason.

## Asking questions about a report (chat)

Chat is the one place where you type something into Medi-Scan rather than
uploading it, and the one place where a model's words go straight to your
screen. Five things about it are worth stating plainly.

**Nothing you type is kept.** Not the question, not the answer, not the
conversation. There is no chat table, no chat column, and no log line that
contains any of it — not even at debug level, and not inside an error message.
The conversation lives in your browser tab and in the server's memory for as
long as the answer takes to write. Reload the page and it is gone, which is what
the "Not saved" tag on the panel means. Because nothing is stored, the server
has no history of its own: your browser sends the last few turns back with each
question, and the server caps how many it will accept.

**What the model is given, and nothing else.** One request carries: the masked
report text, the list of values after they were checked in code, up to four
short passages from our own biomarker pages, the recent turns, and your
question. No image, no original file, no name, no account, no report id. The
model has no tools and nothing it can call — it returns text and that is all.
Everything that came from outside sits inside delimiters with `<` and `>`
escaped, so no report, page or question can close a tag and start issuing
instructions. A report that contains "ignore your instructions" is a test case
in our evaluation set, not a surprise.

**What you type is masked too.** The question and every turn of history go
through the same personal-data patterns as report text — email, phone, SSN,
identifier and date-of-birth shapes — before the prompt is built. If you type
your phone number into a question, the model does not see it. This is a second
line, not a licence: masking is best-effort here exactly as it is everywhere
else on this page, so still don't type anything you would mind publishing.

**Every sentence is checked for invented numbers before you see it.** This is
the defence that matters most. A model that cheerfully says "normal is under
130" has invented a clinical cutoff, and a reader has no way to tell that from a
number copied off their own report. So answers are not streamed to you a token
at a time. They are buffered into whole sentences, and each sentence is checked
against the numbers that are actually allowed: the values and reference ranges
printed on your report, the numbers in its text, the counts, and the collection
date. A sentence quoting anything else stops the answer there — you get a line
saying so instead of the rest. The curated biomarker pages deliberately state no
numeric ranges at all, so retrieved text can never widen that set.

The honest limit: the guard catches a wrong *number*, not a wrong *word*. It
cannot tell that a normal value has been described as high. The flags come from
code and the model is told to trust them rather than judge values itself, and
the chat evaluation measures how often an answer contradicts a flag.

**Diagnosis and treatment questions are declined.** The model is told to start
an answer with one of three markers — a normal answer, a refusal, or "that isn't
in your report". The server reads that marker, removes it, and uses it to label
the answer in the UI; it never reaches your browser as text. Ask whether you
have a condition, which drug to take, what dose, or how urgent something is, and
you get a short note pointing at a doctor or nurse, plus whatever the report
does say. The evaluation measures that refusal rate against a target of 100%,
and measures over-refusal on ordinary questions too, because a demo that refuses
everything is not safe, just useless.

## What is stored, and for how long

| Data | Stored | Deleted |
|---|---|---|
| Uploaded file | Temp file on disk, never the database | In a `finally` block right after text extraction |
| Images prepared for OCR (a photo converted, or a scanned page rendered) | Temp file on disk, never the database | As soon as that page has been read, and in a `finally` block on failure |
| Raw extracted text | Memory only — never disk, never the database | When masking finishes |
| Masked text, values, summary | Database | Guests: 24 hours. Signed-in users: 30 days. |
| Chat questions and answers | **Never stored**, for guests or users | Discarded after streaming |
| User identity | Google `sub`, display name, role, timestamps | On account deletion, or 180 days without sign-in |
| Session and refresh tokens | SHA-256 hash only | On expiry; revoked refresh tokens kept 1 day for reuse detection |
| Aggregated daily counters | Counts only — no ids, no content | 90 days |

**Never stored:** your email address, your Google profile photo, your Google ID
token, any raw token, or any report content in logs. Reports cascade-delete, so
deleting a session or an account removes everything below it.

**Never logged:** report content (including inside exception messages), and
tokens of any kind. Logs hold ids, status changes, error codes, token counts and
timings.

## Account and token handling

- **Google is the only sign-in method.** There are no passwords, no password
  reset, and no email sending.
- **Every Google ID token is fully verified** — signature against Google's
  published keys, issuer, audience, and expiry.
- **Access tokens live 15 minutes** and are held in memory in the browser only,
  so they are gone on reload.
- **Refresh tokens rotate on every use.** Reusing a revoked one is treated as a
  stolen copy and revokes the whole family.
- **Admin is granted by config only** (`ADMIN_GOOGLE_SUBS`). No endpoint or UI
  grants roles, and admin endpoints return aggregated counts only. The role is
  recomputed from that list on every sign-in **and every refresh**, so adding or
  removing a subject takes effect within one access-token lifetime without the
  user signing in again. `app_user.role` is a cache of the config, never the
  source of truth.
- **A bad bearer token never falls back to guest access.** If `Authorization` is
  present and invalid the request fails, even when a valid `X-Session-Token` is
  sent too — otherwise a client with an expired token would silently keep
  working as a guest and see an empty report list instead of being told to
  refresh.
- **History and trends are owner-scoped on every query**, and the admin stats
  endpoint returns aggregate counts only, never report content or user
  identifiers.

### Sign-in in the browser

Signing in happens entirely in the page — there is no redirect and no callback
URL. Google Identity Services hands the page an ID token, the page posts it to
`/api/auth/google`, and the app's own tokens come back.

**What the browser keeps, and where:**

| Thing | Where | Survives a reload? |
|---|---|---|
| Google ID token | nowhere | — it is posted once and dropped, never stored and never logged |
| Access token | a JavaScript variable | no |
| Refresh token | `sessionStorage`, key `ms.refresh` | yes, for that tab only |
| Guest session token | `sessionStorage`, key `ms.guest` | yes, for that tab only |
| Display name and role | memory, refetched after a reload | no |

Nothing about a signed-in user is written to `localStorage`, to a cookie, or to
a URL. The display name is rendered as text and never put in a page title, a log
line, or a query key.

**The duplicated-tab guard.** "Duplicate tab" copies `sessionStorage`, which
means the copy opens holding the original's refresh token. Refresh tokens
rotate, so the second of the two tabs to refresh presents a token that has
already been used — indistinguishable, from the server, from a stolen copy — and
reuse detection correctly revokes the family and signs the user out of both
tabs. The user did nothing wrong and would have no idea why.

So each tab announces its own tab id on a `BroadcastChannel` before it uses any
token, and waits up to 150 ms. If a live tab answers that it already owns that
id, this tab is the copy: it deletes `ms.refresh` and signs in again through
Google auto sign-in, under a family of its own. The handshake carries a tab id
and nothing else, a tab never answers its own announcement, and a lone tab
therefore cannot mistake itself for a duplicate. Where `BroadcastChannel` is
unavailable the check is skipped and a duplicated tab costs one sign-out.

The guest token is deliberately left alone: it does not rotate and has no reuse
detection, so two tabs sharing one is harmless.

### What an account actually is

Four columns and two timestamps: Google's `sub`, a display name for the header,
a role, and when the account was created and last used. No email address, no
profile photo, no locale, and the Google ID token is discarded the moment it has
been verified. The database has nowhere to put any of them.

The display name comes from Google's `name` claim, trimmed and capped at 100
characters, or `"User"` when Google sends nothing usable. It is treated as
untrusted input, because the account holder controls it.

### Deleting your account

`DELETE /api/me` removes the account and everything linked to it — reports,
report text, biomarkers, summaries, and every refresh token — in one cascading
delete. There is no soft delete and no recovery window.

It takes effect **immediately**, not when your access token expires. That token
would still pass every signature and claim check for up to 15 minutes, so each
authenticated request also checks that the account still exists; once it does
not, every endpoint answers `401 AUTH_REQUIRED`. Jobs still processing a deleted
report stop at their next status write, because the row they would write to is
gone.

### Signing key rotation

Access tokens are signed with HS256 and carry a `kid` naming the key. The server
signs with `JWT_CURRENT_KID` and accepts any key listed in `JWT_SIGNING_KEYS`, so
rotating is: add the new key and make it current, keep the old one for 15
minutes, then remove it. Nobody is signed out — refresh tokens are random values
checked against the database and do not depend on the signing key, so even a
leftover token signed by a removed key just causes one silent refresh.

Rotate at least yearly, whenever the backend moves hosts, and immediately if a
key may have leaked. Keys are never committed, never logged, and never shared
between local and production. Generate one with `openssl rand -base64 32`.

### One operational rule about logging

Nothing in the application logs a raw token, a Google `sub`, or a display name,
at any level, and a test drives every auth path and reads the logs back to prove
it.

That guarantee stops at the framework boundary. **Do not enable `TRACE` on
`org.springframework.jdbc`, `org.postgresql`, or `org.hibernate` in production.**
Those loggers print bound SQL parameter values by design, which means they print
`google_sub` and `display_name` along with everything else. No application code
can prevent it; the only control is not switching them on.

### The browser-storage trade-off

Tokens are kept in the browser (access token in memory, refresh token in
`sessionStorage`) rather than in cookies. Cookies would need the frontend and API
on one site, which needs a paid domain, and Safari blocks third-party cookies.

The cost of that choice: a script-injection (XSS) bug could read
`sessionStorage`. What limits the damage:

- a strict Content Security Policy, with Google Identity Services as the only
  permitted third-party script — no analytics, no tag managers
- no `dangerouslySetInnerHTML` anywhere; model output renders as markdown with
  raw HTML disabled
- 15-minute access tokens
- refresh-token rotation with reuse detection
- one refresh-token family per tab, so tabs can't race each other — including
  duplicated tabs, which drop the token they inherit (see "Sign-in in the
  browser" above)

**The one thing in `localStorage` is the theme.** Key `ms-theme`, value `dark`
or `light`, written only by the theme toggle. It is a display preference, not a
credential and not personal data, and it has to outlive the tab or a returning
visitor gets a flash of the wrong theme on every visit. Tokens stay in memory
and `sessionStorage`; nothing else is ever written to `localStorage`.

### In the browser

Report content — values, test names, the summary — is held in one place only:
the in-memory TanStack Query cache for the open tab. Closing the tab drops it.
It is never written to `localStorage` or `sessionStorage`, never put in a URL,
a page title, a log line or an error message, and never sent anywhere but the
API. Page titles stay generic ("Your report · Medi-Scan") for the same reason:
a title ends up in browser history and in the window switcher.

Report ids do appear in URLs. That is safe because an id is a random UUID and
is useless without the owner's credential — someone else's report returns 404,
not 403, so a leaked URL confirms nothing. `/reports/` is excluded in
`robots.txt` and report pages carry `noindex`.

A chat conversation is held the same way, and more strictly: it is React state
belonging to one report in one tab, never written to `localStorage` or
`sessionStorage`, never put in a URL or a page title, and never logged to the
console. It does not survive a reload, and two tabs showing the same report do
not share one. The panel says "Not saved" because that is literally true on both
ends of the connection.

An uploaded file stays a `File` object in memory until the request is sent, is
never read into storage, and the file input is reset afterwards. The browser
contacts three origins and no others: the site itself, the one API origin named
in `connect-src`, and `accounts.google.com` for sign-in. Nothing about a report
is ever sent to Google — the only thing that crosses to it is the sign-in
exchange itself.

### The Content Security Policy

```text
default-src 'self';
script-src 'self' 'nonce-{random}' 'strict-dynamic';
style-src 'self' 'unsafe-inline' https://accounts.google.com/gsi/style;
img-src 'self' data: blob:;
font-src 'self';
connect-src 'self' {API origin} https://accounts.google.com/gsi/;
frame-src https://accounts.google.com/gsi/;
object-src 'none';
base-uri 'none';
form-action 'self';
frame-ancestors 'none';
upgrade-insecure-requests        (production only)
```

A fresh nonce is generated per request in the Next.js middleware and put on
every script the app emits — Next's own bootstrap and chunk-loading scripts, and
the small inline script that sets the theme before first paint.
`'strict-dynamic'` then lets those scripts load the chunks they need without
host allowlisting. `'unsafe-eval'` is added in development only, for React Fast
Refresh; a production response never carries it.

**Why `style-src` keeps `'unsafe-inline'` and `script-src` does not.** React and
Next both write inline `style` attributes — for route transitions, for
measurement, for the font loader — and a strict style policy blocks them, which
breaks the page rather than protecting it. The asymmetry is deliberate and not a
shortcut: an injected stylesheet cannot read `sessionStorage` or call an API,
and the threat this policy exists to stop is script injection reaching the
tokens in browser storage. Styles can leak some things through selectors and
background URLs, which is why `img-src` and `font-src` stay on `'self'` and
`connect-src` names exactly one API origin.

**What the nonce costs.** A per-request nonce means a per-request response, so
pages that carry it cannot be served from Next's full-route static cache; they
render dynamically on every request. The site is a handful of small pages in
front of an API that is dynamic anyway, so the cost is a few milliseconds per
request against the whole point of the policy. Static assets — the JS chunks,
the CSS, the self-hosted fonts — are unaffected and still cached normally.

**The Google Identity Services entries.** Sign-in is the one feature that needs
a third-party origin, and these four lines are the whole of it. `script-src`
does not name Google at all: the GIS library is loaded by a script that already
carries the nonce, and `'strict-dynamic'` extends trust to what that script
loads, so no host allowlist is needed. `frame-src` moves from `'none'` because
GIS renders its button and its One Tap prompt in an iframe from
`accounts.google.com/gsi/`; `connect-src` lets that iframe talk back to Google;
`style-src` covers the stylesheet the library injects for the button. Each entry
is a path under one Google origin, not a wildcard, and Google Identity Services
remains the only third-party origin that is ever allowed — no analytics, no tag
managers, no fonts.

## Authorization

Every report has exactly one owner — a guest session or a user — enforced by a
database check constraint. Report ids are random UUIDs, every report query
filters by the caller's owner id, and **someone else's report returns 404, not
403**, so the API never confirms that an id exists.

## No medical advice

Diagnosis and treatment questions are declined with a pointer to a clinician —
in the summary, and in chat, where the refusal is measured (see the chat section
above). There is deliberately **no `CRITICAL` flag**: critical values use separate
cutoffs that cannot be derived from a reference range, and inventing one would
be the most dangerous thing this app could do.

## Reporting a problem

This is a personal demo project with no bug bounty. If you find a security issue,
please open a GitHub issue — or, if it involves data exposure, contact me
privately rather than filing publicly.
