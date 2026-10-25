// Medi-Scan components — window.MediScan. Documentation types (not type-checked).
import type { ReactNode, CSSProperties } from 'react';

export type IconName = 'upload' | 'arrow-right' | 'arrow-down' | 'arrow-up-right' | 'chevron-left' | 'chevron-right' | 'user' | 'sun' | 'moon' | 'plus' | 'file' | 'camera' | 'menu' | 'close' | 'sources' | 'trash' | 'chart' | 'clock';
export type Flag = 'LOW' | 'NORMAL' | 'HIGH' | 'UNKNOWN';

/** Pill button or link. Solid for the main action on a section, outline beside it, accent for the one submit that sends a report. */
export interface ButtonProps { variant?: 'solid' | 'outline' | 'accent'; size?: 'sm' | 'md' | 'lg'; block?: boolean; icon?: IconName; iconEnd?: IconName; href?: string; disabled?: boolean; type?: 'button' | 'submit'; onClick?: () => void; children: ReactNode; style?: CSSProperties; className?: string; }
/** Round 44px icon-only button. `label` is required: it becomes aria-label and title. */
export interface IconButtonProps { icon: IconName; label: string; onClick?: () => void; pressed?: boolean; expanded?: boolean; size?: number; style?: CSSProperties; className?: string; }
/** Sun/moon IconButton that flips the theme. The consumer owns the theme state. */
export interface ThemeToggleProps { theme: 'dark' | 'light'; onToggle: () => void; style?: CSSProperties; }
/** Inline stroke icon, 24-unit grid, 1.6 stroke, currentColor. */
export interface IconProps { name: IconName; size?: number; strokeWidth?: number; style?: CSSProperties; }
/** Backend health indicator for the top bar. */
export interface StatusPillProps { status: 'online' | 'waking'; short?: boolean; }
/** Mono uppercase section label in accent-ink. */
export interface EyebrowProps { children: ReactNode; style?: CSSProperties; }
/** Lab flag chip. Unknown or unrecognised flags render a neutral dash. */
export interface FlagChipProps { flag: Flag | string; }
/** Small pill label. Default is outlined; tones are filled count chips. */
export interface TagProps { tone?: 'count' | 'high' | 'normal' | 'low'; children: ReactNode; }
/** Segmented control on a surface-2 track. */
export interface TabsProps { items: (string | { value: string; label: string })[]; value: string; onChange: (value: string) => void; label?: string; block?: boolean; }
/** Checkbox with a wrapping label; links inside the label use accent-ink. */
export interface CheckboxProps { checked?: boolean; defaultChecked?: boolean; onChange?: (checked: boolean) => void; children: ReactNode; style?: CSSProperties; }
/** File picker styled as a dashed drop area. `tile` is the compact phone version (two side by side). */
export interface DropzoneProps { variant?: 'wide' | 'tile'; icon?: IconName; title?: ReactNode; hint?: string; accept?: string; capture?: 'environment' | 'user'; onFiles?: (files: FileList) => void; style?: CSSProperties; }
/** Rounded container. `glass` floats over busy grounds; `panel` is the large upload panel. Passing onClick makes it a button. */
export interface CardProps { variant?: 'surface' | 'glass' | 'panel'; selected?: boolean; onClick?: () => void; children: ReactNode; style?: CSSProperties; className?: string; }
/** Numbered sample-report card from GET /api/samples. */
export interface SampleCardProps { num: string; title: string; description: string; markers: string; selected?: boolean; cta?: string; onClick?: () => void; style?: CSSProperties; }
/** Big light numeral with a hairline above and a label below. */
export interface StatFigureProps { value: string; unit?: string; label: string; }
/** One lab value row. `compact` stacks the range under the name for phones. */
export interface BiomarkerRowProps { name: string; value: string; unit?: string | null; range?: string | null; flag: Flag | string; compact?: boolean; }
/** Chat message. Wrap several in a `.ms-chat` column. */
export interface ChatBubbleProps { role: 'user' | 'assistant'; children: ReactNode; }
/** Numbered process steps. Horizontal for desktop (labels only), vertical for phones (active step shows its body). */
export interface StepTimelineProps { steps: { title: string; body?: string }[]; active: number; onSelect?: (index: number) => void; orientation?: 'horizontal' | 'vertical'; style?: CSSProperties; }
/** Decorative particle DNA helix in helix-violet → helix-cyan. aria-hidden. */
export interface HelixProps { width?: number; height?: number; seed?: number; style?: CSSProperties; }
/** Endless scrolling banner of short phrases; every second phrase in accent. aria-hidden. */
export interface MarqueeProps { items: string[]; style?: CSSProperties; }
