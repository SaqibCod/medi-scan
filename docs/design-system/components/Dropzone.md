File picker styled as a dashed drop area (`line-strong` dashes, `accent` on hover).

- **Provide** `onFiles`. Defaults accept PDF, PNG and JPEG, matching the API.
- `wide` (default) on desktop: icon tile, "Drop your report here, or browse", and the limit line "PDF · PNG · JPEG — up to 10 MB".
- `tile` on phones: two side by side, "Choose file" and "Take a photo" (`capture="environment"`, images only), with the limit line once beneath them.
- Check size and type before upload and show the API's message for `FILE_TOO_LARGE` or `UNSUPPORTED_FILE_TYPE`.
