# OpenNLP person-name model

`en-ner-person.bin` backs `OpenNlpNameRule`, which is **off by default**
(`mediscan.mask.opennlp-enabled=false`). Nothing in the default build loads this file.

## Provenance

| | |
|---|---|
| Source URL | `https://opennlp.sourceforge.net/models-1.5/en-ner-person.bin` |
| Size | 5,207,953 bytes |
| SHA-256 | `687a9263d96b37fced707c9f2ac0560f9edaf54658856395555901924f64dbe4` |
| Built | September 2010 (per the zip entry timestamps) |
| Licence | Apache License 2.0, per the Apache OpenNLP project site |
| Verified against | `opennlp-tools` 2.5.12 — `OpenNlpNameRuleTest` loads it and asserts it finds names |

Verify a re-download with:

```bash
sha256sum backend/src/main/resources/opennlp/en-ner-person.bin
```

## Why this file, and why it is a compromise

Apache publishes about 37 pre-trained models to Maven Central, covering sentence
detection, tokenisation, part-of-speech tagging and lemmatisation. **None of them is a
named-entity model.** There is no English person-name model available as a Maven artifact,
so a committed binary is the only option, and this is the only such model the project
offers.

Three things about it are worth knowing before relying on it:

1. **It is a legacy 1.5-era model.** The Apache models page states the 1.5 models remain
   "fully compatible with Apache OpenNLP 2.5.12", which our test confirms. But it is not a
   current Apache release artifact.
2. **The 1.5 download page carries no per-model licence or provenance statement.** The
   Apache 2.0 licence above comes from the project site as a whole, not from a statement
   attached to this file. There is no signature or published checksum to check it against,
   which is why the hash above was computed at download time and recorded here.
3. **It was trained on news text.** Lab reports are not news text. Expect it to miss names
   in header blocks and to occasionally propose a test name as a person. The false-positive
   filters in `OpenNlpNameRule` exist for the second problem, and the label-based rules in
   `MaskRules` are what the project actually relies on for the first.

See `SECURITY.md` for the measured false-positive and false-negative behaviour, and
`docs/phase-2-lld.md` section 19 item 4 for the decision to keep the rule but default it off.
