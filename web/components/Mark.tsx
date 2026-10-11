// Kept's mark: three glass tiles, each a step above the last.
export function Mark({ size = 26 }: { size?: number }) {
  const tiles = [
    { x: 3, y: 22, h: 11, from: '#63e2f1', to: '#8ca6ff', edge: '#6f8ff2' },
    { x: 10, y: 18.5, h: 13, from: '#8ca6ff', to: '#c3a6ff', edge: '#8b7cf0' },
    { x: 17, y: 15, h: 15, from: '#c3a6ff', to: '#f6b4e4', edge: '#b58af0' },
  ];
  return (
    <svg width={size} height={size} viewBox="0 0 28 24" aria-hidden="true">
      {tiles.map((t, i) => (
        <g key={i}>
          <defs>
            <linearGradient id={`m${i}`} x1="0" y1="1" x2="0.4" y2="0">
              <stop offset="0" stopColor={t.from} /><stop offset="1" stopColor={t.to} />
            </linearGradient>
          </defs>
          <polygon points={`${t.x + 5},${t.y} ${t.x + 7},${t.y - 1} ${t.x + 10},${t.y - t.h - 1} ${t.x + 8},${t.y - t.h}`} fill={t.edge} />
          <polygon points={`${t.x},${t.y} ${t.x + 5},${t.y} ${t.x + 8},${t.y - t.h} ${t.x + 3},${t.y - t.h}`} fill={`url(#m${i})`} />
        </g>
      ))}
    </svg>
  );
}
