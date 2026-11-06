Numbered process steps with a hairline rail and a glowing active dot.

- **Provide** `steps` (`title`, `body`), the `active` index and `onSelect`.
- Horizontal (desktop): dots on a rail with "01  Upload" labels. Show the active step's body in a large paragraph above it, and pair it with chevron IconButtons.
- `orientation="vertical"` (phones): the active step expands to show its body, and every row is a 48px+ tap target.
- The active dot is `accent` with a `glow` halo; idle dots are `dot-idle`.
