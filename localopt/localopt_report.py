"""Summarise the CSV written by the LocalOptTiming patch (-Ddotty.opt.timing=<file>).

usage: python3 localopt_report.py opt.csv
"""
import csv, sys, math
from collections import defaultdict

PASSES = ["start", "nullness", "dce", "boxUnbox", "copyProp", "staleStores", "redundantCasts",
          "pushPop", "storeLoad", "staleHandlers", "simplifyJumps", "localVars", "lineNumbers"]
K = len(PASSES)


def load(path):
    rows = []
    for r in csv.reader(open(path)):
        rows.append(dict(
            owner=r[0], name=r[1], desc=r[2], insns=int(r[3]), insnsAfter=int(r[4]), locals=int(r[5]),
            wall=int(r[6]), cpu=int(r[8]), alloc=int(r[9]), rounds=int(r[10]),
            passCpu=[int(x) for x in r[11:11 + K]], passAlloc=[int(x) for x in r[11 + K:11 + 2 * K]]))
    return rows


def main(path):
    rows = sorted(load(path), key=lambda r: -r["cpu"])
    n, C = len(rows), sum(r["cpu"] for r in rows)
    A, W = sum(r["alloc"] for r in rows), sum(r["wall"] for r in rows)
    print(f"methods={n} cpu={C/1e9:.2f}s wall={W/1e9:.2f}s alloc={A/2**30:.2f}GiB")

    cs = sorted(r["cpu"] for r in rows)
    print("cpu percentiles:", "  ".join(f"p{p}={cs[min(n-1, int(n*p/100))]/1e3:.1f}us" for p in (50, 90, 99, 99.9)))
    acc = 0
    for i, r in enumerate(rows, 1):
        acc += r["cpu"]
        if i in (1, 10, 100, n // 100, n // 10):
            print(f"  slowest {i:>5} methods ({100*i/n:5.2f}%) = {100*acc/C:5.1f}% of cpu")

    print("\ncpu by pass:")
    for i, p in enumerate(PASSES):
        t = sum(r["passCpu"][i] for r in rows)
        print(f"  {p:<15} {t/1e9:6.2f}s {100*t/C:5.1f}%")

    def pivot(title, key):
        g = defaultdict(lambda: [0, 0, 0, [0] * K])
        for r in rows:
            e = g[key(r)]
            e[0] += 1; e[1] += r["cpu"]; e[2] += r["rounds"]
            for i in range(K):
                e[3][i] += r["passCpu"][i]
        print(f"\n{title}  (cpu ms; count; mean rounds; top passes)")
        for k in sorted(g):
            c, t, rd, pc = g[k]
            top = sorted(range(K), key=lambda i: -pc[i])[:3]
            print(f"  {str(k):>12} n={c:>6} cpu={t/1e6:9.1f} ({100*t/C:4.1f}%) rounds={rd/c:4.2f}  "
                  + " ".join(f"{PASSES[i]}={100*pc[i]/max(t,1):.0f}%" for i in top))

    pivot("by instruction count", lambda r: 2 ** int(math.log2(max(r["insns"], 1))))
    pivot("by rounds", lambda r: r["rounds"])

    print("\nslowest 20:  cpu(ms) alloc(MB) rounds insns locals")
    for r in rows[:20]:
        print(f"  {r['cpu']/1e6:8.1f} {r['alloc']/2**20:8.1f} {r['rounds']:>3} {r['insns']:>5} {r['locals']:>4}  {r['owner']}.{r['name']}")


if __name__ == "__main__":
    main(sys.argv[1])
