import test from 'node:test';
import assert from 'node:assert/strict';
import {readFile, readdir, stat} from 'node:fs/promises';
import {fileURLToPath} from 'node:url';
import path from 'node:path';

const here = path.dirname(fileURLToPath(import.meta.url));
const project = path.resolve(here, '../../..');

test('v5 is a continuous 52 second film with three usable recording windows', async () => {
  const {v5Film, pickups} = await import('../data.ts');
  let end = 0;
  for (const shot of v5Film.shots) {
    assert.equal(shot.from, end);
    assert.ok(shot.duration >= 180);
    end += shot.duration;
  }
  assert.equal(end, 3120);
  assert.equal(v5Film.durationInFrames, end);
  assert.equal(v5Film.fps, 60);
  assert.equal(pickups.length, 3);
  for (const slot of pickups) {
    const shot = v5Film.shots.find((s) => s.id === slot.shot);
    assert.ok(shot);
    assert.ok(slot.seconds >= shot.duration / 60);
  }
});

test('lite APK and first use download amounts keep separate scopes', async () => {
  const {facts} = await import('../data.ts');
  assert.equal(facts.size.liteMb, 24);
  assert.equal(facts.size.runtimeMb, 128);
  assert.equal(facts.size.firstUseMb, 152);
  assert.equal(facts.size.fullMb, 149);
  assert.equal(facts.performance.watch.seconds, 13);
  assert.equal(facts.performance.watch.ramGb, 3);
  assert.equal(facts.accessibility.loopSeconds, 1.45);
});

test('pet playback uses decoded real frames with verified source provenance', async () => {
  const manifest = JSON.parse(await readFile(path.join(project, 'public/pet/provenance.json'), 'utf8'));
  assert.equal(manifest.archiveBytes, 127443364);
  assert.equal(manifest.archiveSha256, 'f033d46d4eb9cf3ad850b5015e4d3165b8fbf6c2218dc055d6ecc13b6faaf433');
  assert.equal(manifest.renderArchiveBytes, 393448027);
  assert.equal(manifest.renderArchiveSha256, 'e83fa01171fb275e3a4268c48df04a1eea6e22c84c1096721942dd479667f7cf');
  assert.equal(manifest.fps, 24);
  for (const clip of manifest.clips) {
    assert.ok(clip.frames >= 40);
    assert.ok((await stat(path.join(project, `public/pet/${clip.id}/0000.png`))).size > 1000);
  }
});

test('v5 uses deterministic animation and fixed vertical padding', async () => {
  const walk = async (dir) => {
    const rows = await readdir(dir, {withFileTypes: true});
    return (await Promise.all(rows.map((e) => e.isDirectory() ? walk(path.join(dir, e.name)) : [path.join(dir, e.name)]))).flat();
  };
  for (const file of (await walk(path.join(project, 'src/v5'))).filter((f) => /\.tsx?$/.test(f))) {
    const source = await readFile(file, 'utf8');
    assert.doesNotMatch(source, /@keyframes|requestAnimationFrame|setTimeout|Math\.random\(/, file);
    assert.doesNotMatch(source, /padding(?:Top|Bottom)?\s*:\s*['"][^'"]*%/, file);
  }
});
