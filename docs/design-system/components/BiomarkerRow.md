One row of the results table: test name, value + unit, reference range, flag.

- **Provide** the fields of one `Biomarker`: `testName` → `name`, `rawValue` → `value`, `unit`, `referenceRangeText` → `range`, `flag`.
- Values and ranges use the mono `data` / `meta` styles so digits line up. Missing values show "—".
- `compact` (phones) stacks the range under the name and keeps value and flag on the right.
- When `biomarkerSlug` is set, make the name a link to `/biomarkers/{slug}`.
