Pill-shaped button or link for every action on the site.

- **Provide** `children` (the label), and either `onClick` or `href` (renders an `<a>`).
- `variant="solid"` (`solid` fill) is the main action of a section: "Upload a report". Use one per view.
- `variant="outline"` sits beside a solid button for the alternative: "Try a sample report", "Sign in with Google".
- `variant="accent"` (`accent` fill, `on-accent` text) is reserved for the submit that sends data: "Analyze report", and the sticky phone CTA. Keep it `disabled` until consent is ticked; disabled shows `surface-2` / `ink-3`.
- `size="lg"` (52px) on phones, `size="sm"` (36px) for floating helpers like "Scroll for more"; `block` makes it full width on phones.
- `icon` sits before the label, `iconEnd` after (use `arrow-right` for forward actions). Labels are sentence case verbs.
