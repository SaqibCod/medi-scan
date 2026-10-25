Chat message bubble for the report chat.

- **Provide** `role` (`user` or `assistant`) and the text. Stack bubbles in a `.ms-chat` column.
- User: `solid` fill, right-aligned, tail bottom-right. Assistant: `accent-soft` fill, left-aligned, tail bottom-left.
- Stream assistant text in as `token` events arrive. After `sources`, add a `.ms-sources` line linking the biomarker pages used.
- A `DECLINED` or `NOT_IN_CONTEXT` answer keeps the assistant bubble but adds a one-line note in `ink-3` below it.
