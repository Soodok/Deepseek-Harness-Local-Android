import {Easing, interpolate} from 'remotion';

export const ease = Easing.bezier(0.16, 1, 0.3, 1);
export const clamp = (value: number, min = 0, max = 1) => Math.max(min, Math.min(max, value));
export const phase = (frame: number, start = 0, duration = 36) => ease(clamp((frame - start) / duration));
export const mix = (a: number, b: number, t: number) => a + (b - a) * t;
export const travel = (frame: number, start = 0, distance = 56) => mix(distance, 0, phase(frame, start));
export const key = (frame: number, frames: number[], values: number[]) => interpolate(frame, frames, values, {
  extrapolateLeft: 'clamp', extrapolateRight: 'clamp', easing: ease,
});
