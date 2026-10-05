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

Before any text reaches a model or the database, it goes through:

1. **Regex rules** for social security numbers, phone numbers, email addresses,
   ID and MRN patterns, and dates of birth — dates only when they sit next to a
   label such as "DOB" or "Date of Birth", so the collection date survives for
   trend charts.
2. **An OpenNLP name finder** for person names.
3. **A value integrity check**: any match that overlaps a numeric value or a
   reference range in a results row is skipped rather than masked, and counted as
   a masking conflict. Corrupting a lab value is treated as worse than leaving a
   borderline match in place.

Matches are replaced with typed placeholders such as `[NAME]` and `[PHONE]`.

**Known limits.** Masking will miss:

- names it doesn't recognise as names — unusual spellings, names in languages the
  model wasn't trained on, names that are also common words
- personal details in free-text comment fields and physician notes
- identifiers in unusual formats, or ones that look like lab values
- anything in a layout the regex rules don't anticipate — a name inside a logo, a
  footer, a signature block, or an "Ordered by" field
- text that OCR mangled badly enough to break the patterns

The test suite checks masking in **both** directions: that personal data is
removed, *and* that every lab value and reference range survives unchanged. It
uses synthetic reports with names in deliberately awkward positions.

## What is stored, and for how long

| Data | Stored | Deleted |
|---|---|---|
| Uploaded file | Temp file on disk, never the database | In a `finally` block right after text extraction |
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
  grants roles, and admin endpoints return aggregated counts only.

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
- one refresh-token family per tab, so tabs can't race each other

## Authorization

Every report has exactly one owner — a guest session or a user — enforced by a
database check constraint. Report ids are random UUIDs, every report query
filters by the caller's owner id, and **someone else's report returns 404, not
403**, so the API never confirms that an id exists.

## No medical advice

Diagnosis and treatment questions are declined with a pointer to a clinician.
There is deliberately **no `CRITICAL` flag**: critical values use separate
cutoffs that cannot be derived from a reference range, and inventing one would
be the most dangerous thing this app could do.

## Reporting a problem

This is a personal demo project with no bug bounty. If you find a security issue,
please open a GitHub issue — or, if it involves data exposure, contact me
privately rather than filing publicly.
