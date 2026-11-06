import type { CSSProperties } from "react";

/**
 * The design system's icon set.
 *
 * These are the only icons the app uses — no Lucide, no other library, no emoji (root
 * CLAUDE.md). A new icon is a new entry here, drawn on the same 24-unit grid with a 1.6
 * stroke and round caps, and it goes into the design system too.
 *
 * The path data is copied verbatim from docs/design-system/components/bundle.js, including
 * its `CIRCLE:cx,cy,r` shorthand, so a change to the design system stays a readable diff
 * against this table rather than a re-drawing.
 */
const PATHS = {
  upload: ["M12 16V4", "M6 10l6-6 6 6", "M4 20h16"],
  "arrow-right": ["M5 12h14", "M13 6l6 6-6 6"],
  "arrow-down": ["M12 4v16", "M6 14l6 6 6-6"],
  "arrow-up-right": ["M7 17L17 7", "M9 7h8v8"],
  "chevron-left": ["M15 6l-6 6 6 6"],
  "chevron-right": ["M9 6l6 6-6 6"],
  user: ["CIRCLE:12,8,4", "M4 21c1.5-4 4.5-6 8-6s6.5 2 8 6"],
  sun: [
    "CIRCLE:12,12,4",
    "M12 2.5v2M12 19.5v2M2.5 12h2M19.5 12h2M5.3 5.3l1.4 1.4M17.3 17.3l1.4 1.4M5.3 18.7l1.4-1.4M17.3 6.7l1.4-1.4",
  ],
  moon: ["M20 14.5A8 8 0 0 1 9.5 4a8 8 0 1 0 10.5 10.5z"],
  plus: ["CIRCLE:12,12,10", "M12 8v8M8 12h8"],
  file: ["M14 3H7a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2V8z", "M14 3v5h5", "M12 18v-6M9 15l3-3 3 3"],
  camera: ["M4 8h3l2-3h6l2 3h3v11H4z", "CIRCLE:12,13,3.5"],
  menu: ["M4 9h16M4 15h16"],
  close: ["M6 6l12 12M18 6L6 18"],
  sources: ["M4 5h16v14H4z", "M8 9h8M8 13h5"],
  trash: ["M4 7h16", "M9 7V4h6v3", "M6 7l1 13h10l1-13"],
  chart: ["M4 20V4", "M4 20h16", "M7 15l4-5 3 3 5-7"],
  clock: ["CIRCLE:12,12,9", "M12 7v5l3 2"],
} as const satisfies Record<string, readonly string[]>;

export type IconName = keyof typeof PATHS;

export const ICON_NAMES = Object.keys(PATHS) as IconName[];

export interface IconProps {
  name: IconName;
  size?: number;
  strokeWidth?: number;
  style?: CSSProperties;
  className?: string;
}

/**
 * Always `aria-hidden`: an icon never carries meaning on its own. An icon-only control gets
 * its name from `IconButton`'s required `label`, and an icon beside text is decoration.
 */
export function Icon({ name, size = 18, strokeWidth = 1.6, style, className }: IconProps) {
  return (
    <svg
      width={size}
      height={size}
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth={strokeWidth}
      strokeLinecap="round"
      strokeLinejoin="round"
      aria-hidden="true"
      focusable="false"
      style={style}
      className={className}
    >
      {PATHS[name].map((shape, i) => {
        if (shape.startsWith("CIRCLE:")) {
          const [cx, cy, r] = shape.slice("CIRCLE:".length).split(",");
          return <circle key={i} cx={cx} cy={cy} r={r} />;
        }
        return <path key={i} d={shape} />;
      })}
    </svg>
  );
}
