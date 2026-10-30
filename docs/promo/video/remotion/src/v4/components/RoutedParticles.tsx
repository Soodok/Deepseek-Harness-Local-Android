import {cubicPoint, pathThrough, progress, type Point} from '../motion';
import {theme} from '../../theme';

export type ParticleRoute = {id: string; points: readonly [Point, Point, Point, Point]; color?: string; delay?: number};

export const RoutedParticles = ({routes, frame, duration = 90, width = 950, height = 800, holdTrails = false}: {
  routes: readonly ParticleRoute[]; frame: number; duration?: number; width?: number; height?: number; holdTrails?: boolean;
}) => <svg width={width} height={height} viewBox={`0 0 ${width} ${height}`} style={{position: 'absolute', inset: 0, overflow: 'visible'}} data-v4-graphics="particles">
  <defs><filter id="v4-particle-glow" x="-100%" y="-100%" width="300%" height="300%"><feGaussianBlur stdDeviation="6" /></filter></defs>
  {routes.map((route, index) => {
    const raw = progress(frame, route.delay ?? index * 5, duration);
    const p = cubicPoint(route.points, raw);
    const color = route.color ?? theme.colors.accent;
    const tail = Array.from({length: 20}, (_, j) => cubicPoint(route.points, Math.max(0, raw - (19 - j) * .018)));
    const settled = raw >= .999;
    const trailOpacity = settled ? (holdTrails ? .23 : .07) : .6;
    return <g key={route.id}>
      <path d={`M${route.points[0].x},${route.points[0].y} C${route.points[1].x},${route.points[1].y} ${route.points[2].x},${route.points[2].y} ${route.points[3].x},${route.points[3].y}`} fill="none" stroke={color} strokeWidth="1" opacity={holdTrails ? .16 : .05} />
      <path d={pathThrough(tail)} fill="none" stroke={color} strokeWidth="9" opacity={trailOpacity * .5} filter="url(#v4-particle-glow)" />
      <path d={pathThrough(tail)} fill="none" stroke={color} strokeWidth="2.2" opacity={trailOpacity} strokeLinecap="round" />
      {!settled && <><circle cx={p.x} cy={p.y} r="10" fill={color} opacity=".25" filter="url(#v4-particle-glow)" /><circle cx={p.x} cy={p.y} r="3.4" fill="#E9F8FF" /></>}
    </g>;
  })}
</svg>;
