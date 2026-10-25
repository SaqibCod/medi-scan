Sun/moon IconButton that switches between the Dark and Light themes.

- **Provide** the current `theme` and an `onToggle` handler; the consumer owns the state and sets `data-theme` on `<html>`.
- Shows a sun in dark mode and a moon in light mode; its label says what a press will do ("Switch to light mode").
- Placement: desktop, the last item in the header, right after "Sign in with Google" with a 12px gap. Phone, in the pinned top bar between the status pill and the menu button.
- Start from the device setting (`prefers-color-scheme`) and remember the visitor's choice.
