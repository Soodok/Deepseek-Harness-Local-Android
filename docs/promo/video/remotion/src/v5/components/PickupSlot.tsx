import type {CSSProperties} from 'react';
import {useCurrentFrame} from 'remotion';
import {C, F} from './Studio';

export const PickupSlot = ({hint, seconds, width = 650, height = 430, style}: {
  hint: string; seconds: number; width?: number; height?: number; style?: CSSProperties;
}) => {
  const frame = useCurrentFrame();
  const compact = width < 560;
  return <div data-v5-pickup={hint} style={{position: 'relative', width, height, borderRadius: 24,
    border: `2px dashed ${C.purple}99`, background: 'linear-gradient(135deg,#182238F2,#111C2DF7)',
    boxShadow: `inset 0 0 65px ${C.purple}0B,0 24px 65px #00000045`, ...style}}>
    <div style={{position: 'absolute', left: 22, top: 20, color: C.purple, fontFamily: F.mono,
      fontSize: compact ? 20 : 23, letterSpacing: '.08em'}}>PICKUP / 画中画</div>
    <div style={{position: 'absolute', right: 16, top: 14, padding: '9px 15px', borderRadius: 9,
      background: `${C.purple}20`, color: '#DDD2FF', fontSize: compact ? 23 : 25}}>建议录屏 {seconds}s</div>
    <div style={{position: 'absolute', inset: '76px 24px 38px', display: 'flex', flexDirection: 'column',
      justifyContent: 'center', alignItems: 'center', textAlign: 'center', gap: 19}}>
      <svg width="70" height="57" viewBox="0 0 70 57" aria-hidden="true" style={{opacity: .75 + .15 * Math.sin(frame / 36)}}>
        <path d="M17 4H4v14M53 4h13v14M4 39v14h13M66 39v14H53" fill="none" stroke={C.purple} strokeWidth="2"/>
        <path d="M24 28h22M35 17v22" fill="none" stroke={C.purple} strokeWidth="2"/>
      </svg>
      <div data-v5-text style={{fontSize: compact ? 27 : 31, color: '#D8C9FB', fontWeight: 700, lineHeight: 1.25}}>此处待补录：</div>
      <div data-v5-text style={{fontSize: compact ? 26 : 32, color: C.white, lineHeight: 1.5, maxWidth: width - 68}}>{hint}</div>
    </div>
    <div style={{position: 'absolute', bottom: 14, left: 22, fontSize: compact ? 18 : 21, color: '#98A1BD'}}>替换此区域即可 · 标题与数据保留</div>
  </div>;
};
