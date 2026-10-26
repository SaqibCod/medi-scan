import type { CSSProperties, ReactNode } from "react";

export interface EyebrowProps {
  children: ReactNode;
  style?: CSSProperties;
}

/**
 * Mono uppercase label above a section title. One per section, never on a card.
 *
 * Write the text in sentence case — CSS uppercases it, so a screen reader still reads words
 * rather than spelling out letters.
 */
export function Eyebrow({ children, style }: EyebrowProps) {
  return (
    <p className="ms-eyebrow" style={style}>
      {children}
    </p>
  );
}
