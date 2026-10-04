import type { CSSProperties } from "react";

export type IconName =
  | "book"
  | "shelf"
  | "device"
  | "link"
  | "arrow"
  | "download"
  | "check"
  | "close"
  | "back"
  | "refresh"
  | "trash"
  | "plus"
  | "minus"
  | "logout";

const paths: Record<IconName, string> = {
  book: "M3 4h7l2 2 2-2h7v15h-7l-2 2-2-2H3V4Zm9 2v15",
  shelf: "M4 4v15M9 4v15M14 6l5 12M2 21h20",
  device: "M6 2h12v20H6V2Zm4 17h4",
  link: "m10 13 4-4M8 16l-1 1a4 4 0 0 1-6-6l5-5a4 4 0 0 1 6 0m0 12a4 4 0 0 0 6 0l5-5a4 4 0 0 0-6-6l-1 1",
  arrow: "M4 12h15m-6-6 6 6-6 6",
  download: "M12 3v12m-5-5 5 5 5-5M4 16v5h16v-5",
  check: "m5 12 4 4L19 6",
  close: "m6 6 12 12M6 18 18 6",
  back: "M20 12H5m6-6-6 6 6 6",
  refresh: "M20 10a8 8 0 1 0-2 8M20 4v6h-6",
  trash: "M3 6h18M9 6V3h6v3M6 6l1 15h10l1-15M10 10v7m4-7v7",
  plus: "M12 4v16M4 12h16",
  minus: "M4 12h16",
  logout: "M10 3H4v18h6m-1-9h12m-5-5 5 5-5 5",
};

export function Icon({
  name,
  size = 20,
  style,
}: {
  name: IconName;
  size?: number;
  style?: CSSProperties;
}) {
  return (
    <svg
      width={size}
      height={size}
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth="1.6"
      strokeLinecap="square"
      strokeLinejoin="miter"
      aria-hidden="true"
      style={style}
    >
      <path d={paths[name]} />
    </svg>
  );
}
