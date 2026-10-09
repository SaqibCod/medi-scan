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

Masking will miss:

- **names in a column layout with no colon** — the labelled-name rule requires
  `Label: Name`, which is how lab reports punctuate headers, but a
  space-aligned `Patient Name    Jane Roe` is missed
- names with no label and no honorific anywhere near them — in a logo, a footer,
  or a scanned signature
- names it cannot recognise as names: unusual spellings, non-Latin scripts, names
  that are also common words
- personal details written into free-text comment fields and physician notes
- identifiers in formats the label list does not anticipate
- text that OCR mangled badly enough to break the patterns

The test suite checks masking in **both** directions — that personal data is
removed, *and* that every lab value and reference range survives byte for byte,
column spacing included. A separate canary test runs a unique fake name and id
through the whole pipeline and asserts they appear in no log line, no database
column, no model prompt, and no response body.

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
