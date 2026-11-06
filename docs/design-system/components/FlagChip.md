Lab flag chip for a biomarker's `flag`: LOW, NORMAL, HIGH or UNKNOWN.

- **Provide** `flag` straight from the API. Anything unrecognised renders as UNKNOWN (a neutral dash), as the contract requires.
- HIGH is orange (`flag-high-*`), LOW is blue (`flag-low-*`), NORMAL is green (`flag-normal-*`). The word is always shown, so the meaning never depends on colour.
- Never use red: a flag is information from the report, not an alarm.
