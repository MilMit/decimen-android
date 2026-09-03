const LN2 = 0.6931471805599453;
function dlog(x) {
  let e = 0;
  let m = x;
  while (m >= 1.5) { m /= 2; e++; }
  while (m < 0.75) { m *= 2; e--; }
  const z = (m - 1) / (m + 1);
  const z2 = z * z;
  let term = z;
  let sum = 0;
  for (let n = 1; n <= 21; n += 2) { sum += term / n; term *= z2; }
  return e * LN2 + 2 * sum;
}
function solitonCdf(k) {
  const cdf = new Float64Array(k);
  if (k === 1) { cdf[0] = 1; return cdf; }
  const R = Math.max(1, 0.1 * dlog(k / 0.5) * Math.sqrt(k));
  const spike = Math.min(k, Math.ceil(k / R));
  let total = 0;
  for (let d = 1; d <= k; d++) {
    const rho = d === 1 ? 1 / k : 1 / (d * (d - 1));
    let tau = 0;
    if (d < spike) tau = R / (d * k);
    else if (d === spike) tau = (R * Math.max(0, dlog(R / 0.5))) / k;
    total += rho + tau;
    cdf[d - 1] = total;
  }
  for (let i = 0; i < k; i++) cdf[i] /= total;
  cdf[k - 1] = 1;
  return cdf;
}
function splitmix32(seed) {
  let s = seed | 0;
  return () => {
    s = (s + 0x9e3779b9) | 0;
    let t = s ^ (s >>> 16);
    t = Math.imul(t, 0x21f0aaad);
    t ^= t >>> 15;
    t = Math.imul(t, 0x735a2d97);
    t ^= t >>> 15;
    return t >>> 0;
  };
}
function frameSeed(sessionId, seq) {
  let h = (Math.imul(sessionId + 1, 0x9e3779b1) ^ (seq + 0x85ebca6b)) | 0;
  h = Math.imul(h ^ (h >>> 13), 0xc2b2ae35);
  return (h ^ (h >>> 16)) | 0;
}
function frameIndices(k, cdf, sessionId, seq) {
  const rnd = splitmix32(frameSeed(sessionId, seq));
  const u = rnd() * 2 ** -32;
  let lo = 0, hi = k - 1;
  while (lo < hi) {
    const mid = (lo + hi) >> 1;
    if (cdf[mid] >= u) hi = mid; else lo = mid + 1;
  }
  const d = Math.min(k, lo + 1);
  if (d > k >> 3) {
    const scratch = new Uint32Array(k);
    for (let i = 0; i < k; i++) scratch[i] = i;
    const out = new Array(d);
    for (let i = 0; i < d; i++) {
      const j = i + (rnd() % (k - i));
      const t = scratch[i]; scratch[i] = scratch[j]; scratch[j] = t; out[i] = scratch[i];
    }
    return out;
  }
  const set = new Set();
  while (set.size < d) set.add(rnd() % k);
  return [...set];
}
const cases = [
  [1, 1, 0],
  [2, 42, 1],
  [7, 65535, 2],
  [359, 12345, 0],
  [359, 12345, 1],
  [359, 12345, 123456789],
  [1432, 54321, 4294967295],
];
for (const [k, session, seq] of cases) {
  console.log(`${k}|${session}|${seq}|${frameIndices(k, solitonCdf(k), session, seq).join(',')}`);
}
const rnd = splitmix32(0x12345678);
console.log('rng|' + [rnd(), rnd(), rnd(), rnd(), rnd()].join(','));
