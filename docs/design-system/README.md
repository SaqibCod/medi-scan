Medi-Scan reads a lab report and explains it in plain language. The look is a calm lab at night: a near-black ground, one violet light, thin type, and a particle DNA helix as the only picture. Every screen has a Dark and a Light theme built from the same tokens. Dark is the default; the visitor's device setting and their own toggle choice win.

## Voice and content

- Speak to the reader as "you" and "your report". The product is "Medi-Scan", never "we" in headlines; "we" is fine in error messages ("We couldn't read text from this file.").
- Plain words over medical jargon. Say what the report says, never what it means for the person's health: "Your LDL is above the range printed on your report", not "You have high cholesterol".
- Every page that shows results carries the line "Medi-Scan explains what your report says and is not medical advice."
- Headlines are short statements in sentence case, often split in two with the second half in `faint`: "Your lab report, / in plain language." Buttons are sentence-case verbs: "Upload a report", "Try a sample report", "Analyze report".
- Eyebrows are 2–4 words in the `eyebrow` style: "AI LAB REPORT READER", "WHAT YOU GET", "PRIVACY".
- Numbers are only real product facts (10 MB limit, 24 h guest expiry, 30 days history, 0 chat messages stored). Never invented statistics or testimonials.
- No emoji anywhere.

## Colour

- `bg` is the page ground. Text is `ink` (headlines, body), `ink-2` (supporting paragraphs, nav), `ink-3` (units, ranges, captions). `faint` is only for display text 24px and larger.
- `accent` is the single brand hue. Use it for the one primary submit (`Button` `variant="accent"` with `on-accent` text), the active timeline dot, arrows, focus rings and marquee highlights. Accent-coloured text is always `accent-ink`.
- `solid` / `on-solid` is the high-contrast pill (white on dark, black on light) for main CTAs, selected tabs and the user chat bubble. Hover moves to `solid-hover`.
- Cards are `surface`; floating panels are `glass` with `blur-glass` and a `line` border; light mode adds `shadow-panel`.
- Lab flags have their own pairs: `flag-high-*` (orange), `flag-normal-*` (green), `flag-low-*` (blue). Always show the word with the colour. Never use red: a flag is information, not an alarm.
- `status-ok` and `status-warn` are only for the backend status dot, always beside its word.
- `helix-violet` → `helix-cyan` is reserved for the Helix graphic.
- Gradients appear in exactly two places: the faint radial glow behind the results preview band (`accent` and `helix-cyan` at low alpha over `bg`), and the footer wordmark fading from `foot-top` into `bg`. No other gradients.

## Typography

- Two families from Google Fonts: Geist (`sans`) for everything, Geist Mono (`mono`) for eyebrows, lab values, ranges, dates and flags.
- Display styles are weight 400 (numerals 300) with tight negative tracking: `display-xl` (desktop hero), `display-l` (section openers), `display-m`, `display-mobile` (phone hero), `heading` (statements and phone section titles).
- Oversized numerals (`numeral-xl` on sample cards, `numeral-l` for facts) are a signature. Set units smaller in `ink-3`.
- Body text is `body-l` for hero leads, `body` for reading, `body-s` for cards and tables, `caption` for consent and footer.
- Never bold for emphasis in running text; change colour (`ink` vs `ink-2`) instead. Button labels use weight 500.

## Space, shape and layout

- Spacing steps run `space-1` (4px) to `space-10` (120px). Sections sit `space-10` apart on desktop and `space-9` on phones. Side gutters are `space-8` (56px) on desktop, clamped down to `space-4` (16px) on phones. Content max width is 1360px.
- Everything clickable is a pill (`radius-pill`). Containers step up in radius with size: `radius-sm` icon tiles, `radius-md` drop zones and inputs, `radius-lg` cards, `radius-xl` floating panels, `radius-2xl` feature bands.
- Separate list rows with `line` hairlines, not boxes. Pills and inputs use `line-strong` borders.
- Desktop pages are fluid down to 900px; below that they switch to the phone layout (a stacked single column, `lg` full-width buttons, the pinned top bar, a sticky accent CTA at the bottom).

## Components

Mount from `window.MediScan` (React 18).

- **Actions:** `Button`, `IconButton`, `ThemeToggle`.
- **Inputs:** `Tabs` (Upload file / Paste text), `Dropzone` (wide on desktop; two tiles, "Choose file" and "Take a photo", on phones), `Checkbox` (the required consent).
- **Data:** `FlagChip`, `Tag`, `BiomarkerRow`, `StatFigure`, `SampleCard`, `ChatBubble`.
- **Layout:** `Card` (surface, glass, panel, interactive).
- **Navigation:** `StepTimeline` (horizontal on desktop, vertical on phones).
- **Feedback:** `StatusPill` (online / waking).
- **Brand:** `Helix`, `Marquee`. The helix appears once per page, at most.

Header order on desktop: wordmark · nav links · spacer · `StatusPill` · "Sign in with Google" outline button · `ThemeToggle`. On phones the bar is pinned: wordmark · spacer · `StatusPill short` · `ThemeToggle` · menu `IconButton`. The menu opens a sheet with large links and a full-width sign-in button.

## Iconography

- Use only `Icon`: inline stroke SVG on a 24-unit grid, 1.6 stroke, round caps and joins, `currentColor`. Arrows point where the action goes (`arrow-right` forward, `arrow-up-right` on cards that open something).
- Icons never stand alone without a text label or an `IconButton` `label`.
- There is no logo file. The wordmark is the text "MEDI·SCAN" in Geist 600, 0.14em tracking. The sign-in button uses the generic `user` icon here; in the real app, follow Google's sign-in branding rules.

## Motion

- The Helix floats slowly (9s) while its dots twinkle (4.2s). The marquee scrolls in 38s on desktop and 26s on phones. The waking status dot pulses.
- Hover: cards lift 4px and border in `accent`; outline pills fill with `solid`. Theme changes fade background and text over 0.3s.
- All looping motion stops under `prefers-reduced-motion`.

## Accessibility

- Every text pair above meets 4.5:1 in both themes (3:1 for `faint` display text). Check new pairs in both Dark and Light.
- The focus ring is 2px solid `accent` with a 3px offset, on every interactive element.
- Touch targets are at least 44px. Icon-only buttons always have a `label`.
- Flags, status and steps never rely on colour alone: each carries a word or number.
- Decorative pieces (Helix, Marquee, footer wordmark) are `aria-hidden`; anything they say also appears as real text.
