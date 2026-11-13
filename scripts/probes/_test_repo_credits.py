"""关于页仓库清单的引用核验：每个条目都必须能在这份仓库的代码里找到落点。

为什么需要它：这份清单是手工维护的，很容易漂成「我以为我用过」。一旦列进
没用到的项目，既是对上游的错误署名，也让用户对着看不懂的东西发懵。
"""
import io, os, re, sys

ROOT = os.getcwd()
ok = 0
bad = 0

def check(name, cond, detail=""):
    global ok, bad
    if cond:
        ok += 1
        print("PASS  " + name)
    else:
        bad += 1
        print("FAIL  " + name + ("  -> " + detail if detail else ""))

credits = io.open(
    os.path.join(ROOT, "app/src/main/java/app/dsh/mobile/RepoCredits.kt"), encoding="utf-8"
).read()

# 收集全部被扫描的源文本（代码 + 构建脚本 + 资源）
hay = []
for base in ("app/src/main/java", "app/src/main/cpp", "app/src/main/assets/gates",
             "app/src/main/assets/tools", "app/src/main/assets/android-plugins",
             "scripts"):
    d = os.path.join(ROOT, base)
    for dirpath, _, files in os.walk(d):
        for f in files:
            if f.endswith((".kt", ".java", ".c", ".h", ".mjs", ".js", ".py", ".sh", ".json", ".yml", ".yaml")):
                p = os.path.join(dirpath, f)
                if "RepoCredits.kt" in p:
                    continue
                try:
                    hay.append(io.open(p, encoding="utf-8", errors="ignore").read())
                except OSError:
                    pass
for f in ("app/build.gradle.kts", "build.gradle.kts", "settings.gradle.kts"):
    p = os.path.join(ROOT, f)
    if os.path.exists(p):
        hay.append(io.open(p, encoding="utf-8", errors="ignore").read())
corpus = "\n".join(hay)

# 名字 -> 必须在语料里出现的证据词（任一命中即可）
EVIDENCE = {
    "Node.js": ["node", "libnode"],
    "pnpm": ["pnpm"],
    "llama.cpp": ["llama-server", "llama.cpp", "llamaServer"],
    "bge-small-zh-v1.5": ["bge-small", "bge_small", "CompendiumLabs"],
    "Termux packages": ["termux-packages", "packages.termux.dev", "termux"],
    "termux-app": ["terminal-view", "termux-app", "com.termux"],
    "Alpine Linux aports": ["alpine"],
    "Ubuntu base rootfs": ["ubuntu"],
    "Shizuku": ["shizuku", "Rikka"],
    "KernelSU": ["/data/adb/ksu", "KernelSU", "kernelsu"],
    "Kotlin Coroutines": ["kotlinx-coroutines", "kotlinx.coroutines"],
    "Java-WebSocket": ["Java-WebSocket", "java_websocket", "org.java-websocket"],
    "Apache MINA SSHD": ["sshd-core", "mina-sshd", "org.apache.sshd"],
    "XZ for Java": ["org.tukaani", "xz:1.10", "org.tukaani:xz"],
    "ML Kit Text Recognition": ["mlkit", "text-recognition", "TextRecognition"],
    "Material Components": ["com.google.android.material", "material:1.12"],
    "edge-tts": ["edge-tts", "EdgeTts", "edge_tts"],
    "dsh-pet-bridge": ["dsh-pet-bridge", "pet-bridge"],
    "dsh-pet-mobile-assets": ["dsh-pet-mobile-assets"],
    "dsh-solution-explorer": ["dsh-solution-explorer"],
    "dsh-hardssh": ["dsh-hardssh", "hardssh"],
}

repos = re.findall(r'Repo\(\s*\n?\s*"([^"]+)"', credits)
print("清单条目数：%d" % len(repos))

# 1. 每个条目都要有证据
for name in repos:
    key = None
    for k in EVIDENCE:
        if name.startswith(k) or k in name:
            key = k
            break
    if key is None:
        # 我们自己的那几个仓库：名字里带链接，不算外部依赖
        if any(s in name for s in ("upstream host", "this app", "container rootfs",
                                   "on-device recognition", "third-party UI plugin")):
            continue
        check("条目「%s」有核验规则" % name, False, "新条目必须补 EVIDENCE 映射")
        continue
    hit = next((w for w in EVIDENCE[key] if w in corpus), None)
    check("「%s」在代码里有引用" % name, hit is not None, "一个证据词都没命中")

# 2. 明确不该出现的（已判定为「没用过」）
FORBIDDEN = {
    "cloudflared": "只在一句 UI 提示里出现，既非依赖也未打包",
    "onnxruntime": "是 dsh-asr-service 的依赖，不在本仓",
    "sherpa-onnx": "是 dsh-asr-service 的依赖，不在本仓",
    "dsh-web-mobile-fix": "只存在于插件市场目录数据里",
    "dsh-ai-providers": "只存在于插件市场目录数据里",
    "dsh-plugin-kit": "只存在于插件市场目录数据里",
    "DeepSeek-Harness-Desktop-Ultra": "只存在于插件市场目录数据里",
}
for k, why in FORBIDDEN.items():
    check("不再列出 %s" % k, k not in credits, why)

print("-" * 44)
print("passes=%d fails=%d" % (ok, bad))
sys.exit(1 if bad else 0)
