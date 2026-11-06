export interface StatFigureProps {
  value: string;
  unit?: string;
  label: string;
}

/**
 * Big light numeral with a hairline above and a label below.
 *
 * Only real product facts go here — 10 MB, 24 h, 30 days, 0 chat messages stored. Never an
 * invented statistic (design system, "Voice and content").
 */
export function StatFigure({ value, unit, label }: StatFigureProps) {
  return (
    <div className="ms-stat">
      <div className="ms-stat__value">
        {value}
        {unit && <span className="ms-stat__unit"> {unit}</span>}
      </div>
      <p className="ms-stat__label">{label}</p>
    </div>
  );
}
