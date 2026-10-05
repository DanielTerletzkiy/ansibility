import json, re, sys
cases = json.load(open(sys.argv[1], encoding='utf-8')) if len(sys.argv) > 1 else json.load(sys.stdin)
out = {"python": sys.version.split()[0]}
import warnings
for cid, pat, fl, inp, rep in cases:
    flags = (re.I if 'I' in fl else 0) | (re.M if 'M' in fl else 0)
    with warnings.catch_warnings(record=True) as w:
        warnings.simplefilter('always')
        try:
            count = 2 if cid == 'count_2' else 0
            r = re.compile(pat, flags).sub(rep, inp, count)
            out[cid] = {"ok": r}
        except Exception as e:
            out[cid] = {"err": type(e).__name__ + ": " + str(e)}
        if w:
            out[cid]["warn"] = str(w[0].message)
print(json.dumps(out, ensure_ascii=False))
