// The signature picture: a staircase of thin glass tiles climbing across the page, leaning like a run
// of dominoes caught mid turn. `lit` of the `total` tiles are coloured, counted from the bottom; the
// rest are the same glass with the colour almost drained out. Mirrors GlassStairs in the Android app.
const IRIS = ['#63e2f1', '#8ca6ff', '#c3a6ff', '#f6b4e4', '#a9f0dd'];

function hex(c: string) { return [1, 3, 5].map((i) => parseInt(c.slice(i, i + 2), 16)); }
function mix(a: number[], b: number[], t: number) { return a.map((v, i) => Math.round(v + (b[i] - v) * t)); }
function iris(p: number): number[] {
  const x = (((p % 1) + 1) % 1) * IRIS.length;
  const i = Math.floor(x) % IRIS.length;
  return mix(hex(IRIS[i]), hex(IRIS[(i + 1) % IRIS.length]), x - Math.floor(x));
}
const rgb = (c: number[]) => `rgb(${c[0]},${c[1]},${c[2]})`;

export function GlassStairs({ lit = 30, total = 30, height = 380 }: { lit?: number; total?: number; height?: number }) {
  const W = 1200;
  const H = height;
  const n = Math.max(2, total);
  const pitch = (W * 0.98) / (n - 1);
  const wide = pitch * 2.3;
  const slabH = H * 0.36;
  const white = [255, 255, 255];
  const accent = [74, 58, 232];
  const tiles = [];
  for (let i = 0; i < n; i++) {
    const t = i / (n - 1);
    const bx = W * 0.02 + pitch * i;
    const by = H * (0.93 - 0.52 * t) + Math.sin(t * 2 * Math.PI) * H * 0.05;
    const lean = ((-54 + 92 * t + Math.sin(t * 2 * Math.PI) * 12) * Math.PI) / 180;
    const up = [Math.sin(lean) * slabH, -Math.cos(lean) * slabH];
    const ax = [Math.cos(lean) * (wide / 2), Math.sin(lean) * (wide / 2)];
    const a = [bx - ax[0], by - ax[1]];
    const b = [bx + ax[0], by + ax[1]];
    const d = Math.min(wide * 0.16, 9);
    const on = i < lit;
    const wash = on ? 0 : 0.66;
    const low = mix(iris(t * 1.2), white, wash);
    const high = mix(iris(t * 1.2 + 0.34), white, wash);
    const pts = (p: number[][]) => p.map((q) => `${q[0].toFixed(1)},${q[1].toFixed(1)}`).join(' ');
    const face = [a, b, [b[0] + up[0], b[1] + up[1]], [a[0] + up[0], a[1] + up[1]]];
    const edge = [b, [b[0] + d, b[1] - d * 0.45], [b[0] + d + up[0], b[1] - d * 0.45 + up[1]], [b[0] + up[0], b[1] + up[1]]];
    const id = `t${i}`;
    tiles.push(
      <g key={i} className="tile" style={{ animationDelay: `${(i * 0.045).toFixed(2)}s` }}>
        <defs>
          <linearGradient id={id} gradientUnits="userSpaceOnUse" x1={a[0]} y1={a[1]} x2={a[0] + up[0]} y2={a[1] + up[1]}>
            <stop offset="0" stopColor={rgb(low)} /><stop offset="1" stopColor={rgb(high)} />
          </linearGradient>
          <linearGradient id={`${id}g`} gradientUnits="userSpaceOnUse" x1={a[0] + up[0]} y1={a[1] + up[1]} x2={b[0]} y2={b[1]}>
            <stop offset="0" stopColor="#fff" stopOpacity="0.6" /><stop offset="1" stopColor="#fff" stopOpacity="0" />
          </linearGradient>
        </defs>
        <polygon points={pts(edge)} fill={rgb(mix(low, accent, on ? 0.38 : 0.06))} />
        <polygon points={pts(face)} fill={`url(#${id})`} fillOpacity={on ? 0.9 : 0.6} />
        <polygon points={pts(face)} fill={`url(#${id}g)`} />
        <polygon points={pts(face)} fill="none" stroke={on ? '#fff' : '#d9ddea'} strokeOpacity={on ? 0.9 : 1} strokeWidth="1.2" strokeLinejoin="round" />
      </g>,
    );
  }
  return (
    <div className="ribbon-wrap" aria-hidden="true">
      <svg className="ribbon" viewBox={`0 0 ${W} ${H}`} preserveAspectRatio="xMidYMid meet">{tiles}</svg>
    </div>
  );
}
