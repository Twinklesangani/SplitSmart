const paths = {
  activity: <path d="M3 12h4l3-8 4 16 3-8h4" />,
  arrowDown: <><path d="M12 5v14" /><path d="m19 12-7 7-7-7" /></>,
  arrowLeft: <><path d="m15 18-6-6 6-6" /><path d="M20 12H9" /></>,
  arrowRight: <><path d="M4 12h16" /><path d="m13 5 7 7-7 7" /></>,
  arrowUp: <><path d="M12 19V5" /><path d="m5 12 7-7 7 7" /></>,
  basket: <><path d="m5 10 2 10h10l2-10" /><path d="m3 10 3-6 4 6 2-6 3 6 4-6 2 6" /><path d="M9 14v2m6-2v2" /></>,
  car: <><path d="m5 11 1.5-5h11l1.5 5" /><path d="M3 11h18v7H3z" /><path d="M6 18v2m12-2v2M6 14h.01M18 14h.01" /></>,
  check: <path d="m5 12 4 4L19 6" />,
  close: <path d="m18 6-12 12M6 6l12 12" />,
  group: <><path d="M16 21v-2a4 4 0 0 0-4-4H8a4 4 0 0 0-4 4v2" /><circle cx="10" cy="7" r="4" /><path d="M20 21v-2a4 4 0 0 0-3-3.87M16 3.13a4 4 0 0 1 0 7.75" /></>,
  home: <><path d="m3 10 9-7 9 7" /><path d="M5 9v12h14V9M9 21v-7h6v7" /></>,
  menu: <path d="M4 6h16M4 12h16M4 18h16" />,
  plus: <path d="M12 5v14M5 12h14" />,
  receipt: <><path d="M4 3h16v18l-3-2-3 2-2-2-3 2-2-2-3 2z" /><path d="M8 8h8m-8 4h8m-8 4h4" /></>,
  settings: <><circle cx="12" cy="12" r="3" /><path d="m19.4 15 .1.1 1.4 1.1-1.4 2.4-1.7-.7a8 8 0 0 1-1.7 1l-.3 1.8h-2.8l-.3-1.8a8 8 0 0 1-1.7-1l-1.7.7-1.4-2.4 1.4-1.1a7 7 0 0 1 0-2l-1.4-1.1 1.4-2.4 1.7.7a8 8 0 0 1 1.7-1l.3-1.8h2.8l.3 1.8a8 8 0 0 1 1.7 1l1.7-.7 1.4 2.4-1.4 1.1a7 7 0 0 1-.1 2Z" /></>,
  spark: <><path d="m12 3 1.8 5.2L19 10l-5.2 1.8L12 17l-1.8-5.2L5 10l5.2-1.8L12 3Z" /><path d="m19 16 .8 2.2L22 19l-2.2.8L19 22l-.8-2.2L16 19l2.2-.8L19 16Z" /></>,
  utensils: <><path d="M7 3v7m4-7v7M7 7h4m-2 3v11M17 3v18m0-18c2 2 3 5 3 8h-3" /></>,
  wallet: <><rect x="3" y="5" width="18" height="16" rx="2" /><path d="M3 9h18M16 15h.01M6 5V3h12v2" /></>,
}

export default function Icon({ name, size = 20, strokeWidth = 1.8, ...props }) {
  return (
    <svg aria-hidden="true" width={size} height={size} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={strokeWidth} strokeLinecap="round" strokeLinejoin="round" {...props}>
      {paths[name] ?? paths.spark}
    </svg>
  )
}
