import type { ReactNode } from 'react';

// A small icon set drawn here (24 x 24, a thin line, no fill) so the shop needs no icon font and no image.
// Icons are decorative: the text or the aria-label next to them says what they mean.

const PATHS = {
  search: (
    <>
      <circle cx="11" cy="11" r="6.5" />
      <path d="M16 16l4.5 4.5" />
    </>
  ),
  cart: (
    <>
      <path d="M3 4h2.2l2.1 10.2a1.5 1.5 0 0 0 1.5 1.2h7.9a1.5 1.5 0 0 0 1.5-1.1L20 8H6.2" />
      <circle cx="9.5" cy="19.5" r="1.4" />
      <circle cx="17" cy="19.5" r="1.4" />
    </>
  ),
  user: (
    <>
      <circle cx="12" cy="8" r="3.8" />
      <path d="M4.5 20c.8-3.8 3.6-6 7.5-6s6.7 2.2 7.5 6" />
    </>
  ),
  'chevron-down': <path d="M6 9.5l6 6 6-6" />,
  'chevron-left': <path d="M14.5 6l-6 6 6 6" />,
  'chevron-right': <path d="M9.5 6l6 6-6 6" />,
  close: <path d="M6 6l12 12M18 6L6 18" />,
  filter: <path d="M4 5h16l-6.2 7.4V19l-3.6-1.8v-4.8L4 5z" />,
  bag: (
    <>
      <path d="M5 8h14l-1 12H6L5 8z" />
      <path d="M9 8V6.5a3 3 0 0 1 6 0V8" />
    </>
  ),
  check: <path d="M5 12.5l4.5 4.5L19 7.5" />,
  minus: <path d="M6 12h12" />,
  plus: <path d="M12 6v12M6 12h12" />,
  grid: (
    <>
      <rect x="4" y="4" width="6.5" height="6.5" rx="1.3" />
      <rect x="13.5" y="4" width="6.5" height="6.5" rx="1.3" />
      <rect x="4" y="13.5" width="6.5" height="6.5" rx="1.3" />
      <rect x="13.5" y="13.5" width="6.5" height="6.5" rx="1.3" />
    </>
  ),
  tag: (
    <>
      <path d="M3.5 12.5V4.5h8l9 9-8 8-9-9z" />
      <circle cx="8" cy="9" r="1.2" />
    </>
  ),
  phone: (
    <>
      <rect x="7" y="2.8" width="10" height="18.4" rx="2.2" />
      <path d="M11 18h2" />
    </>
  ),
  shirt: <path d="M8.5 4L4 7l2 3.5 2-1V20h8V9.5l2 1L20 7l-4.5-3a3.6 3.6 0 0 1-7 0z" />,
  book: (
    <>
      <path d="M5 4.5A1.5 1.5 0 0 1 6.5 3H19v15H6.5A1.5 1.5 0 0 0 5 19.5v-15z" />
      <path d="M5 19.5A1.5 1.5 0 0 0 6.5 21H19v-3" />
    </>
  ),
  sofa: (
    <>
      <path d="M5 11V8.5A2.5 2.5 0 0 1 7.5 6h9A2.5 2.5 0 0 1 19 8.5V11" />
      <path d="M3 13a2 2 0 0 1 4 0v2h10v-2a2 2 0 0 1 4 0v4.5H3V13z" />
      <path d="M6 17.5V20M18 17.5V20" />
    </>
  ),
  basket: (
    <>
      <path d="M4 10h16l-1.6 9.2a1.5 1.5 0 0 1-1.5 1.3H7.1a1.5 1.5 0 0 1-1.5-1.3L4 10z" />
      <path d="M8.5 10l3-6M15.5 10l-3-6" />
    </>
  ),
  ball: (
    <>
      <circle cx="12" cy="12" r="8.5" />
      <path d="M12 3.5v17M3.5 12h17M6 6c2.4 2.2 2.4 9.8 0 12M18 6c-2.4 2.2-2.4 9.8 0 12" />
    </>
  ),
  cube: (
    <>
      <path d="M12 3l8 4.2v9.6L12 21l-8-4.2V7.2L12 3z" />
      <path d="M12 12l8-4.8M12 12L4 7.2M12 12v9" />
    </>
  ),
  car: (
    <>
      <path d="M5 17h14v-5l-2-5H7l-2 5v5z" />
      <path d="M5 12h14M7.5 17v2.5M16.5 17v2.5" />
      <circle cx="8" cy="14.5" r="1" />
      <circle cx="16" cy="14.5" r="1" />
    </>
  ),
  sparkle: (
    <>
      <path d="M12 3l1.9 5.1L19 10l-5.1 1.9L12 17l-1.9-5.1L5 10l5.1-1.9L12 3z" />
      <path d="M18.5 16l.8 2.2 2.2.8-2.2.8-.8 2.2-.8-2.2-2.2-.8 2.2-.8.8-2.2z" />
    </>
  ),
  fridge: (
    <>
      <rect x="6" y="2.8" width="12" height="18.4" rx="2" />
      <path d="M6 11h12M9.5 7v1.5M9.5 14v3" />
    </>
  ),
} satisfies Record<string, ReactNode>;

export type IconName = keyof typeof PATHS;

interface Props {
  name: IconName;
  /** Pixel size of the square. */
  size?: number;
  className?: string;
}

export function Icon({ name, size = 20, className }: Props) {
  return (
    <svg
      className={className}
      width={size}
      height={size}
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth="1.8"
      strokeLinecap="round"
      strokeLinejoin="round"
      aria-hidden="true"
      focusable="false"
    >
      {PATHS[name]}
    </svg>
  );
}

/** The icon for a category, picked from words in its name. Anything unknown gets a plain tag. */
export function categoryIcon(category: string): IconName {
  const name = category.toLowerCase();
  if (/electronic|phone|computer|laptop/.test(name)) return 'phone';
  if (/cloth|fashion|apparel|wear/.test(name)) return 'shirt';
  if (/book|stationery/.test(name)) return 'book';
  if (/furniture/.test(name)) return 'sofa';
  if (/grocer|food|kitchen/.test(name)) return 'basket';
  if (/sport|outdoor|fitness/.test(name)) return 'ball';
  if (/toy|game/.test(name)) return 'cube';
  if (/auto|car\b|vehicle/.test(name)) return 'car';
  if (/beauty|personal|care|health/.test(name)) return 'sparkle';
  if (/appliance/.test(name)) return 'fridge';
  return 'tag';
}
