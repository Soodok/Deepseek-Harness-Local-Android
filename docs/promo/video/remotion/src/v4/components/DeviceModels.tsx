import type {ReactNode} from 'react';
import {EngineSurface} from './EngineSurface';
import {theme} from '../../theme';

export const WatchModel = ({size = 540, ready = true, progress = 1}: {size?: number; ready?: boolean; progress?: number}) => {
  const ratio = size / 540;
  return <div data-device="watch" style={{position: 'relative', width: size, height: size * 1.58, transformStyle: 'preserve-3d'}}>
    <div style={{position: 'absolute', left: '30%', right: '30%', top: 0, bottom: 0,
      borderRadius: 74 * ratio, border: `${2 * ratio}px solid #5C718333`,
      background: 'linear-gradient(90deg,#121C24,#2C3B47 15%,#1D2B35 28%,#192630 72%,#34434F 86%,#0C141C)',
      boxShadow: 'inset 12px 0 20px #00000055, inset -12px 0 20px #00000055'}} />
    <div style={{position: 'absolute', left: '32%', right: '32%', top: '2%', height: '27%', borderRadius: 56 * ratio,
      background: 'repeating-linear-gradient(0deg,transparent 0px,transparent 12px,#00000055 13px,#7990A022 15px,transparent 17px)', opacity: .6}} />
    <div style={{position: 'absolute', left: '32%', right: '32%', bottom: '2%', height: '27%', borderRadius: 56 * ratio,
      background: 'repeating-linear-gradient(0deg,transparent 0px,transparent 12px,#00000055 13px,#7990A022 15px,transparent 17px)', opacity: .6}} />
    {Array.from({length: 4}, (_, i) => <div key={i} style={{position: 'absolute', width: 11 * ratio, height: 18 * ratio, left: '49%', top: `${80 + i * 3.5}%`, borderRadius: 8, background: '#081018', boxShadow: '0 1px 0 #7C92A344'}} />)}
    <div style={{position: 'absolute', right: -12 * ratio, top: '44%', width: 27 * ratio, height: 58 * ratio,
      borderRadius: 10, background: 'repeating-linear-gradient(0deg,#8394A1 0px,#213542 3px,#1A2933 6px)', border: '1px solid #8CA1AD55', boxShadow: '5px 0 15px #00000088'}} />
    <div style={{position: 'absolute', right: -3 * ratio, top: '54%', width: 14 * ratio, height: 40 * ratio, borderRadius: 5, background: '#3C5060'}} />
    <div style={{position: 'absolute', top: '18.35%', left: 0, width: size, height: size, borderRadius: '50%',
      background: 'conic-gradient(from 210deg,#273A46 0deg,#607988 48deg,#182A35 89deg,#94AAB6 142deg,#253F50 180deg,#111F2A 238deg,#7E97A7 295deg,#273A46 360deg)',
      border: '2px solid #7696AA77', boxShadow: `0 36px 60px #00000099,inset 0 0 0 ${5 * ratio}px #07111C,inset 0 0 0 ${9 * ratio}px #758B9B55,0 0 90px ${theme.colors.accent}18`}}>
      <div style={{position: 'absolute', inset: 21 * ratio, borderRadius: '50%', overflow: 'hidden', background: '#0A1016',
        boxShadow: `0 0 0 ${4 * ratio}px #000000,inset 0 0 30px #000000`}}><EngineSurface round ready={ready} progress={progress} /></div>
      <div style={{position: 'absolute', inset: 10 * ratio, borderRadius: '50%', borderTop: '2px solid #EBF8FF88', borderLeft: '1px solid #B5D7E344', pointerEvents: 'none'}} />
      <div style={{position: 'absolute', inset: 17 * ratio, borderRadius: '50%', background: 'linear-gradient(140deg,#F5FAFF10,transparent 38%,transparent 70%,#7DD3FC08)', pointerEvents: 'none'}} />
    </div>
  </div>;
};

export const PhoneModel = ({width = 300, height = 620, children, muted = false}: {width?: number; height?: number; children?: ReactNode; muted?: boolean}) => <div data-device="phone" style={{
  position: 'relative', width, height, borderRadius: width * .12, padding: 10, boxSizing: 'border-box',
  background: 'linear-gradient(120deg,#71899B,#1E303E 16%,#0E1B25 70%,#617B8F)', border: '1.5px solid #7B9AAE88',
  boxShadow: `16px 28px 55px #00000088,inset 0 0 0 3px #101B24`, opacity: muted ? .58 : 1, transformStyle: 'preserve-3d',
}}>
  <div style={{position: 'absolute', right: -4, top: '27%', width: 4, height: '10%', background: '#536F83', borderRadius: 3}} />
  <div style={{position: 'relative', width: '100%', height: '100%', borderRadius: width * .09, overflow: 'hidden', background: '#0B1117'}}>
    {children ?? <EngineSurface />}
    <div style={{position: 'absolute', top: 12, left: '42%', width: '16%', height: 6, borderRadius: 10, background: '#000000'}} />
    <div style={{position: 'absolute', bottom: 11, left: '36%', width: '28%', height: 4, borderRadius: 6, background: '#A9C9D966'}} />
  </div>
</div>;
