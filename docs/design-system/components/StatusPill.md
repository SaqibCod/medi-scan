Backend health indicator for the top bar, driven by `GET /actuator/health` on page load.

- **Provide** `status`: `online` (`status-ok` dot) or `waking` (pulsing `status-warn` dot, while the free-tier server starts). `short` drops "UP…" for phones.
- The dot always sits beside the word, so the state never depends on colour.
- It is a live region (`role="status"`), so the change from waking to online is announced.
