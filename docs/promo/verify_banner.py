from pathlib import Path
import hashlib
import json

from PIL import Image, ImageStat

ROOT = Path(__file__).resolve().parent
EXPECTED = {
    "dsh-mobile-github.png": (1600, 900),
    "dsh-mobile-github@2x.png": (3200, 1800),
    "dsh-mobile-social.png": (1280, 640),
    "dsh-mobile-github.webp": (1600, 900),
}
REQUIRED = {
    "DSH Mobile",
    "DeepSeek Harness.",
    "Now in your pocket.",
    "完整 Agent 引擎，在 Android 上运行。",
    "No Root",
    "No Termux",
    "19 Extensions",
}


def sha256(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def verify():
    missing = [name for name in EXPECTED if not (ROOT / name).is_file()]
    assert not missing, f"Missing deliverables: {missing}"
    evidence = []
    for name, size in EXPECTED.items():
        path = ROOT / name
        with Image.open(path) as image:
            image.load()
            assert image.size == size, (name, image.size, size)
            assert image.mode == "RGB", (name, image.mode)
            extrema = ImageStat.Stat(image).extrema
            assert max(b - a for a, b in extrema) > 200, (name, extrema)
            evidence.append({
                "file": name,
                "dimensions": list(size),
                "mode": image.mode,
                "bytes": path.stat().st_size,
                "sha256": sha256(path),
            })
    manifest = json.loads((ROOT / "manifest.json").read_text(encoding="utf-8"))
    assert REQUIRED.issubset(set(manifest["copy"]))
    assert manifest["version"].startswith("banner-v")
    assert manifest["model_inference"] == "Configured API service"
    for source in manifest["sources"]:
        path = ROOT / source["file"]
        assert path.is_file(), source
        assert sha256(path) == source["sha256"], source
        assert source["origin"], source
    for item in manifest["safe_bounds"]:
        x0, y0, x1, y1 = item["box"]
        assert 0 <= x0 < x1 <= 1600, item
        assert 50 <= y0 < y1 <= 850, item
    result = {
        "result": "PASS",
        "version": manifest["version"],
        "built_at": manifest["built_at"],
        "files": evidence,
        "scope": "Dimensions, decoding, color mode, source integrity, copy and bounds. Visual acceptance is separate.",
    }
    (ROOT / "qa").mkdir(exist_ok=True)
    (ROOT / "qa" / "programmatic-check.json").write_text(
        json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )
    print(json.dumps(result, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    verify()
