"""Decode the verified release WebPs; keep their real artwork and source timing."""
from pathlib import Path
from PIL import Image
from zipfile import ZipFile
import hashlib
import json
import shutil

PROJECT = Path(__file__).resolve().parents[3]
ARCHIVE = Path('C:/Users/Qt/AppData/Local/Temp/pet300-v5-complete.zip')
SOURCE = Path('C:/Users/Qt/AppData/Local/Temp/pet640-v5-unpacked')
HIRES = Path('C:/Users/Qt/AppData/Local/Temp/pet640-v5-complete.zip')
HIRES_SHA = 'e83fa01171fb275e3a4268c48df04a1eea6e22c84c1096721942dd479667f7cf'
TARGET = PROJECT / 'public/pet'
EXPECTED = 'f033d46d4eb9cf3ad850b5015e4d3165b8fbf6c2218dc055d6ecc13b6faaf433'
assert ARCHIVE.stat().st_size == 127443364
assert hashlib.sha256(ARCHIVE.read_bytes()).hexdigest() == EXPECTED
assert HIRES.stat().st_size == 393448027
assert hashlib.sha256(HIRES.read_bytes()).hexdigest() == HIRES_SHA
TARGET.mkdir(parents=True, exist_ok=True)
clips = [('idle', 'a001', 96), ('coding', 'a027', 96), ('celebrate', 'a105', 96)]
frames = {}
union = None
for name, slug, count in clips:
    image = Image.open(SOURCE / 'webp' / f'{slug}.webp')
    rows = []
    for i in range(count):
        image.seek(i)
        frame = image.convert('RGBA')
        alpha = frame.getchannel('A')
        bbox = alpha.point(lambda a: 255 if a >= 24 else 0).getbbox()
        if bbox:
            union = bbox if union is None else (min(union[0], bbox[0]), min(union[1], bbox[1]), max(union[2], bbox[2]), max(union[3], bbox[3]))
        rows.append(frame)
    frames[name] = rows
crop = (max(0, union[0]-6), max(0, union[1]-6), min(640, union[2]+6), min(360, union[3]+6))
manifest = {'repository': 'https://github.com/XiaoPb/dsh-pet-mobile-assets', 'release': 'v1.0.3-update.1', 'archivePath': str(ARCHIVE), 'archiveBytes': ARCHIVE.stat().st_size, 'archiveSha256': EXPECTED, 'renderArchivePath': str(HIRES), 'renderArchiveBytes': HIRES.stat().st_size, 'renderArchiveSha256': HIRES_SHA, 'sourceCanvas': [640, 360], 'crop': crop, 'fps': 24, 'clips': []}
for name, slug, count in clips:
    folder = TARGET / name
    folder.mkdir(exist_ok=True)
    for i, frame in enumerate(frames[name]):
        frame.crop(crop).save(folder / f'{i:04}.png', optimize=True)
    manifest['clips'].append({'id': name, 'source': f'webp/{slug}.webp', 'frames': count, 'fps': 24, 'bytes': sum(p.stat().st_size for p in folder.glob('*.png'))})
shutil.copy2(SOURCE / 'pet-config.json', TARGET / 'pet-config.json')
(TARGET / 'provenance.json').write_text(json.dumps(manifest, ensure_ascii=False, indent=2), encoding='utf-8')
print(json.dumps(manifest, ensure_ascii=False, indent=2))
