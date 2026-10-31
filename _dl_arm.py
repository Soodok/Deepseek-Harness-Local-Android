import os, threading, urllib.request, time

URL = "https://github.com/Soodok/Deepseek-Harness-Local-Android/releases/download/v1.4.53/runtime-arm64-v8a.zip"
BASE = r"E:\Deepeek harness\dsh-android\_runtime_dl"
WORK = os.path.join(BASE, "parts_arm")
OUT = os.path.join(BASE, "runtime-arm64-v8a.zip")
os.makedirs(WORK, exist_ok=True)

req = urllib.request.Request(URL, method="HEAD")
with urllib.request.urlopen(req, timeout=30) as r:
    TOTAL = int(r.headers["Content-Length"])
print("size", TOTAL, flush=True)

N = 12
CHUNK = TOTAL // N + 1


def worker(i):
    start = i * CHUNK
    end = min(start + CHUNK - 1, TOTAL - 1)
    if start > end:
        return
    part = os.path.join(WORK, "part_%02d" % i)
    for _ in range(10):
        try:
            have = os.path.getsize(part) if os.path.exists(part) else 0
            s = start + have
            if s > end:
                return
            rq = urllib.request.Request(URL, headers={"Range": "bytes=%d-%d" % (s, end)})
            with urllib.request.urlopen(rq, timeout=120) as r, open(part, "ab") as f:
                while True:
                    b = r.read(1 << 16)
                    if not b:
                        break
                    f.write(b)
            if os.path.getsize(part) >= end - start + 1:
                return
        except Exception:
            time.sleep(2)


t0 = time.time()
ths = [threading.Thread(target=worker, args=(i,), daemon=True) for i in range(N)]
for t in ths:
    t.start()
while any(t.is_alive() for t in ths):
    time.sleep(5)
    got = sum(os.path.getsize(os.path.join(WORK, "part_%02d" % i))
              for i in range(N) if os.path.exists(os.path.join(WORK, "part_%02d" % i)))
    print("%.1f/%.1f MB %.0f KB/s" % (got / 1e6, TOTAL / 1e6, got / 1024 / max(1, time.time() - t0)), flush=True)
for t in ths:
    t.join()

with open(OUT, "wb") as o:
    for i in range(N):
        p = os.path.join(WORK, "part_%02d" % i)
        if os.path.exists(p):
            o.write(open(p, "rb").read())
print("done", os.path.getsize(OUT), "expected", TOTAL, flush=True)
