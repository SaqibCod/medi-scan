import { InlineNotice } from "@/components/notices/inline-notice";
import { OCR_NOTE } from "@/lib/copy";
import type { TextSource } from "@/lib/api-types";

/**
 * Tells the reader their values were transcribed by OCR, and to check them (LLD 9.3).
 *
 * The backend already refuses to flag a value it read uncertainly — those rows come back as
 * `UNKNOWN` with no number. This note covers what that rule cannot: a digit OCR read
 * *confidently* and wrong. No threshold catches that, so the only honest answer is to say the
 * text came from a photo and let the reader compare it with the page in their hand.
 *
 * `info`, not `warn`. Nothing has gone wrong — the report was read, and this is how.
 */
export function OcrNote({ textSource }: { textSource: TextSource }) {
  // TEXT_LAYER is the only value that hides the note. Anything else, including a value this
  // build's union does not know, is treated as OCR: the cautious reading shows the note rather
  // than hiding it, and a contract that adds a fourth source must not silently suppress it
  // (api-types.ts, unknown enum values).
  if (textSource === "TEXT_LAYER") return null;

  return (
    <InlineNotice
      tone="info"
      icon="camera"
      title={textSource === "MIXED" ? OCR_NOTE.mixedTitle : OCR_NOTE.title}
    >
      <p>{OCR_NOTE.body}</p>
    </InlineNotice>
  );
}
