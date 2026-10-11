import csv, sys, math
K = 13
P = ["start","nullness","dce","boxUnbox","copyProp","staleStores","redundantCasts","pushPop","storeLoad","staleHandlers","simplifyJumps","localVars","lineNumbers"]
SV = [P.index(p) for p in ("boxUnbox", "staleStores", "pushPop")]   # passes that use the SourceValue (prod/cons) analysis
rows = []
for c in csv.reader(open(sys.argv[1])):
    pc = list(map(int, c[11:11+K]))
    rows.append(dict(m=c[0].split('/')[-1]+"."+c[1], insns=int(c[3]), locals=int(c[5]), cpu=int(c[8]),
                     sv=sum(pc[i] for i in SV), tcb=int(c[11+2*K]), cover=int(c[12+2*K]), rounds=int(c[10]), merges=int(c[13+2*K]), work=int(c[14+2*K])))
rows = [r for r in rows if r["insns"] > 0]
T = sum(r["sv"] for r in rows); C = sum(r["cpu"] for r in rows)
print(f"methods={len(rows)} total cpu={C/1e9:.2f}s, SourceValue passes={T/1e9:.2f}s ({100*T/C:.0f}%)")

preds = {
  "insns":                 lambda r: r["insns"],
  "insns*locals":          lambda r: r["insns"] * r["locals"],
  "(insns+cover)*locals":  lambda r: (r["insns"] + r["cover"]) * r["locals"],
  "(insns+cover)*locals^2":lambda r: (r["insns"] + r["cover"]) * r["locals"] ** 2,
  "merges (dynamic)":      lambda r: r["merges"],
  "merge work (dynamic)":  lambda r: r["work"],
  "oracle (actual sv cpu)":lambda r: r["sv"],
}

def spearman(xs, ys):
    def rank(v):
        o = sorted(range(len(v)), key=lambda i: v[i]); r = [0]*len(v)
        for k, i in enumerate(o): r[i] = k
        return r
    a, b = rank(xs), rank(ys); n = len(a); ma = mb = (n-1)/2
    cov = sum((x-ma)*(y-mb) for x, y in zip(a, b)); va = sum((x-ma)**2 for x in a)
    return cov / va

big = [r for r in rows if r["sv"] > 1e6]   # methods costing >1ms in SV passes
print(f"\nrank correlation with SourceValue-pass cpu (all / methods >1ms n={len(big)}):")
for name, f in preds.items():
    print(f"  {name:<26} {spearman([f(r) for r in rows],[r['sv'] for r in rows]):.3f}  {spearman([f(r) for r in big],[r['sv'] for r in big]):.3f}")

print("\nSourceValue cpu saved by skipping the top-N methods ranked by predictor:")
Ns = [10, 25, 50, 100, 250]
print("  " + " "*26 + "".join(f"{'N='+str(n):>9}" for n in Ns))
for name, f in preds.items():
    o = sorted(rows, key=lambda r: -f(r))
    print(f"  {name:<26}" + "".join(f"{100*sum(r['sv'] for r in o[:n])/T:8.1f}%" for n in Ns))

f = preds["(insns+cover)*locals"]
print("\nslowest 15 by actual SV cpu, with predictor rank:")
o = sorted(rows, key=lambda r: -f(r)); rk = {id(r): i+1 for i, r in enumerate(o)}
for r in sorted(rows, key=lambda r: -r["sv"])[:15]:
    print(f"  {r['sv']/1e6:7.1f}ms rank={rk[id(r)]:>5} insns={r['insns']:>5} locals={r['locals']:>4} tcb={r['tcb']:>3} cover={r['cover']:>5} pred={f(r)/1e3:8.0f}k work={r['work']/1e6:7.1f}M  {r['m'][:50]}")
print("\nhighest predictor but cheap (false positives among top 40):")
for r in o[:40]:
    if r["sv"] < 5e6:
        print(f"  {r['sv']/1e6:7.1f}ms rank={rk[id(r)]:>5} insns={r['insns']:>5} locals={r['locals']:>4} tcb={r['tcb']:>3} cover={r['cover']:>5} pred={f(r)/1e3:8.0f}k  {r['m'][:50]}")
