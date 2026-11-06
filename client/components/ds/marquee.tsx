import { Fragment, type CSSProperties } from "react";

export interface MarqueeProps {
  items: string[];
  style?: CSSProperties;
}

/**
 * Endless scrolling banner of short brand phrases, every second one in accent.
 *
 * Decorative (`aria-hidden`): every phrase must also appear as real text elsewhere on the
 * page, because nothing here is announced. The list is repeated twice so the CSS can scroll
 * it by exactly -50% and loop seamlessly.
 */
export function Marquee({ items, style }: MarqueeProps) {
  return (
    <div className="ms-marquee" aria-hidden="true" style={style}>
      <div className="ms-marquee__track">
        {[0, 1].map((copy) =>
          items.map((item, i) => (
            <Fragment key={`${copy}-${i}`}>
              <span className={i % 2 ? "ms-marquee__hl" : undefined}>{item}</span>
              <span className="ms-marquee__sep">/</span>
            </Fragment>
          )),
        )}
      </div>
    </div>
  );
}
