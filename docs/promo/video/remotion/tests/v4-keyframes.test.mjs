import test from 'node:test';
import assert from 'node:assert/strict';
import {v4Film, keyframes, tools, performance, accessibility} from '../src/v4/data.ts';

test('the v4 timeline is contiguous for 59 seconds', () => {
  let cursor = 0;
  for (const shot of v4Film.shots) {
    assert.equal(shot.from, cursor);
    cursor += shot.duration;
  }
  assert.equal(cursor, v4Film.durationInFrames);
  assert.equal(v4Film.shots.length, 12);
});

test('v4 keyframes map to their expanded scene windows', () => {
  assert.equal(v4Film.durationInFrames, 3540);
  assert.equal(v4Film.fps, 60);
  assert.equal(keyframes.devices, 540);
  assert.equal(keyframes.tools, 870);
  assert.equal(keyframes.verify, 1290);
  for (const [id, frame] of Object.entries(keyframes)) {
    const shot = v4Film.shots.find((entry) => entry.id === id);
    assert.ok(shot && frame >= shot.from && frame < shot.from + shot.duration);
  }
});

test('the three studies retain sourced figures and real extension identities', () => {
  assert.equal(performance.watch.seconds, 13);
  assert.equal(performance.watch.memoryGb, 3);
  assert.equal(performance.flagship.seconds, 3.4);
  assert.equal(accessibility.verifyMs, 69);
  assert.equal(accessibility.loopSeconds, 1.45);
  assert.equal(tools.length, 21);
  assert.equal(new Set(tools.map((tool) => tool.id)).size, 21);
  assert.ok(tools.some((tool) => tool.id === 'asr-service'));
  assert.equal(v4Film.shots.at(-1).from + v4Film.shots.at(-1).duration, 3540);
});
