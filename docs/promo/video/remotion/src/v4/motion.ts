import {Easing, interpolate} from 'remotion';

export const clamp = {extrapolateLeft: 'clamp', extrapolateRight: 'clamp'} as const;
export const ease = Easing.bezier(0.16, 1, 0.3, 1);
export const progress = (frame: number, start: number, duration: number) =>
  interpolate(frame, [start, start + duration], [0, 1], {...clamp, easing: ease});

export type Point = {x: number; y: number};
export const cubicPoint = (points: readonly [Point, Point, Point, Point], t: number): Point => {
  const p = Math.max(0, Math.min(1, t));
  const a = 1 - p;
  return {
    x: a ** 3 * points[0].x + 3 * a ** 2 * p * points[1].x + 3 * a * p ** 2 * points[2].x + p ** 3 * points[3].x,
    y: a ** 3 * points[0].y + 3 * a ** 2 * p * points[1].y + 3 * a * p ** 2 * points[2].y + p ** 3 * points[3].y,
  };
};
export const pathThrough = (points: readonly Point[]) => points.map((point, index) => `${index ? 'L' : 'M'}${point.x.toFixed(2)},${point.y.toFixed(2)}`).join(' ');
