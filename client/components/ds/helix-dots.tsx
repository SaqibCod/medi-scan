"use client";

import { useMemo, type CSSProperties } from "react";

export interface HelixDotsProps {
  width: number;
  height: number;
  seed: number;
  style?: CSSProperties;
}

/**
 * Places the particles of the helix.
 *
 * A pure function outside the component on purpose: it seeds a small linear-congruential
 * generator and mutates it as it walks, which is exactly what it should do, but React's
 * compiler cannot tell that the closure never escapes the call. Out here there is no render
 * to escape from, and the result is a plain array the component memoises.
 *
 * Deterministic for a given seed, so the same helix is drawn on every render and in every
 * screenshot.
 */
export function buildHelixDots(W: number, H: number, seed: number): CSSProperties[] {
  const out: CSSProperties[] = [];
  const N = Math.round(H / 12.3);
  const amp = W * 0.27;

  let state = seed;
  const rand = () => {
    state = (state * 9301 + 49297) % 233280;
    return state / 233280;
  };

  const push = (x: number, y: number, depth: number, k = 1) => {
    const size = (1.6 + depth * 4.4) * k * ((W / 560) * 0.35 + 0.65);
    // The gradient runs violet at the top to cyan at the bottom, mixed per dot by its height.
    const mix = Math.round(100 - (y / H) * 100);
    const c = `color-mix(in oklch, var(--helix-violet) ${mix}%, var(--helix-cyan))`;
    out.push({
      left: x,
      top: y,
      width: size,
      height: size,
      background: c,
      opacity: Math.min(0.25 + depth * 0.75, 1),
      boxShadow: `0 0 ${Math.round(3 + depth * 10)}px ${c}`,
      animationDelay: `-${(rand() * 4.2).toFixed(2)}s`,
    });
  };

  for (let i = 0; i < N; i++) {
    const y = i * (H / N);
    const t = i * 0.23;
    const pts: [number, number][] = [];

    // The two strands, half a turn apart.
    for (const ph of [0, Math.PI]) {
      const x = W / 2 + Math.sin(t + ph) * amp;
      const d = (Math.cos(t + ph) + 1) / 2;
      pts.push([x, d]);
      push(x + (rand() - 0.5) * 6, y + (rand() - 0.5) * 6, d);
      if (rand() > 0.45) push(x + (rand() - 0.5) * 34, y + (rand() - 0.5) * 22, d * 0.6, 0.6);
    }

    // Every third turn gets the rungs between the strands.
    if (i % 3 === 0) {
      for (let k = 1; k < 7; k++) {
        const f = k / 7;
        push(
          pts[0][0] + (pts[1][0] - pts[0][0]) * f,
          y,
          (pts[0][1] + (pts[1][1] - pts[0][1]) * f) * 0.85,
          0.7,
        );
      }
    }
  }

  // A dusting of loose particles around the strands.
  for (let j = 0; j < Math.round(N); j++) {
    push(W / 2 + (rand() - 0.5) * W * 0.75, rand() * H, rand() * 0.5, 0.5);
  }

  return out;
}

/**
 * The particle DNA helix — the only picture in the product.
 *
 * Decorative, so `aria-hidden`. Nothing it says appears only here.
 */
export function HelixDots({ width: W, height: H, seed, style }: HelixDotsProps) {
  const dots = useMemo(() => buildHelixDots(W, H, seed), [W, H, seed]);

  return (
    <div className="ms-helix" aria-hidden="true" style={{ width: W, height: H, ...style }}>
      <div className="ms-helix__inner">
        {dots.map((dot, i) => (
          <span key={i} className="ms-helix__dot" style={dot} />
        ))}
      </div>
    </div>
  );
}
