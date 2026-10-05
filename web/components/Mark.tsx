// A ratchet tooth: a slope up, then a hard stop that cannot slide back.
export function Mark({ size = 22 }: { size?: number }) {
  return (
    <svg width={size} height={size} viewBox="0 0 24 24" aria-hidden="true">
      <path d="M3,19 L3,14 L10,7 L10,13 L17,7 L17,19 Z" fill="var(--brass)" />
    </svg>
  );
}
