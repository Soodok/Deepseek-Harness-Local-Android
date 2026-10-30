import {theme} from '../../theme';
import {tools} from '../data';
import {progress} from '../motion';
import {RoutedParticles, type ParticleRoute} from './RoutedParticles';

export const ToolConvergence = ({frame, width = 950, height = 780}: {frame: number; width?: number; height?: number}) => {
  const cx = width / 2;
  const cy = height * .5;
  const routes: ParticleRoute[] = tools.map((tool, i) => {
    const a = (i / tools.length) * Math.PI * 2 - Math.PI / 2;
    const x = cx + Math.cos(a) * (width * .46);
    const y = cy + Math.sin(a) * (height * .47);
    const side = Math.cos(a) > 0 ? 1 : -1;
    return {id: tool.id, delay: 22 + i * 6.4, color: i % 3 === 0 ? theme.colors.accentAlt : i % 3 === 1 ? theme.colors.accent : theme.colors.success,
      points: [{x,y}, {x: x + Math.sin(a) * 100, y: y - Math.cos(a) * 105}, {x: cx + side * 230, y: cy + (i % 4 - 1.5) * 55}, {x: cx + side * 165, y: cy + (i % 5 - 2) * 70}]};
  });
  const names = [{text: 'Python', x: 14, y: 352, color: theme.colors.accent}, {text: 'Go', x: 800, y: 162, color: theme.colors.success},
    {text: 'Clang', x: 735, y: 508, color: theme.colors.accentAlt}, {text: 'FFmpeg', x: 16, y: 526, color: theme.colors.success}];
  return <div style={{position: 'absolute', inset: 0}}>
    <RoutedParticles routes={routes} frame={frame} width={width} height={height} duration={112} holdTrails />
    <svg width={width} height={height} viewBox={`0 0 ${width} ${height}`} style={{position: 'absolute', inset: 0}} data-v4-graphics="tool-orbits">
      <defs><filter id="tool-orbit-glow"><feGaussianBlur stdDeviation="3" /></filter></defs>
      <ellipse cx={cx} cy={cy + 110} rx="415" ry="140" fill="none" stroke={theme.colors.accent} strokeOpacity=".15" strokeWidth="1.5" transform={`rotate(-19 ${cx} ${cy + 110})`} />
      <ellipse cx={cx} cy={cy + 110} rx="415" ry="140" fill="none" stroke={theme.colors.accent} strokeOpacity=".1" strokeWidth="6" filter="url(#tool-orbit-glow)" transform={`rotate(-19 ${cx} ${cy + 110})`} />
      <ellipse cx={cx} cy={cy} rx="270" ry="345" fill="none" stroke={theme.colors.accentAlt} strokeOpacity=".1" transform={`rotate(27 ${cx} ${cy})`} />
      {tools.map((tool, i) => {
        const a = i / tools.length * Math.PI * 2 - Math.PI / 2;
        const p = progress(frame, 22 + i * 6.4, 112);
        const r = 370 - p * 15;
        return <g key={tool.id} transform={`translate(${cx + Math.cos(a) * r} ${cy + Math.sin(a) * (height * .44)})`}>
          <circle r="5" fill={i % 3 === 0 ? theme.colors.accentAlt : theme.colors.accent} opacity={.25 + p * .6} />
          <circle r="15" fill="none" stroke={theme.colors.accent} opacity=".11" />
        </g>;
      })}
    </svg>
    {names.map((name) => <div key={name.text} data-v4-text="tool-name" style={{position: 'absolute', left: name.x, top: name.y,
      fontFamily: theme.fonts.mono, fontSize: 36, lineHeight: 1.2, color: name.color, textShadow: `0 0 24px ${name.color}55`}}>{name.text}</div>)}
  </div>;
};
