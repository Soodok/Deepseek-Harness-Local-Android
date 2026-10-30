import {useCurrentFrame} from 'remotion';
import {theme} from '../../theme';
import {petEvidence} from '../data';
import {progress} from '../motion';
import {StudyStage} from '../components/StudyStage';

const PetActor = ({frame}: {frame: number}) => {
  const peek = progress(frame, 16, 40);
  const work = progress(frame, 82, 74);
  const success = progress(frame, 260, 42);
  const x = 470 + Math.sin(Math.min(1, work) * Math.PI * 1.4) * 84;
  const y = 510 - peek * 58 - work * 23 - success * 16;
  const squash = success > .2 ? 1 + Math.sin(success * Math.PI) * .08 : 1;
  return <div data-v4-pet style={{position: 'absolute', left: x - 110, top: y + 60, width: 420, height: 378, transform: `translate(-50%,-50%) scale(${squash})`, transformOrigin: 'center bottom'}}>
    <div style={{position: 'absolute', left: 8, right: 8, bottom: 0, height: 35, borderRadius: '50%', background: '#00000055', filter: 'blur(9px)', opacity: .7}} />
    <svg width="420" height="378" viewBox="0 0 300 270">
      <defs><linearGradient id="pet-body" x1="0" x2="1"><stop stopColor="#A78BFA" /><stop offset=".55" stopColor="#7DD3FC" /><stop offset="1" stopColor="#6EE7B7" /></linearGradient><filter id="pet-glow"><feGaussianBlur stdDeviation="8" /></filter></defs>
      <ellipse cx="152" cy="186" rx="102" ry="30" fill={theme.colors.accent} opacity=".14" filter="url(#pet-glow)" />
      <path d="M57 153C65 92 106 54 155 57C211 60 246 103 243 159C240 207 203 236 153 235C100 235 51 208 57 153Z" fill="url(#pet-body)" stroke="#D7F5FF" strokeOpacity=".52" strokeWidth="3" />
      <path d="M94 89C77 57 81 33 96 18C113 43 126 60 126 76M188 78C190 49 205 26 223 20C224 48 216 71 204 91" fill="url(#pet-body)" stroke="#D7F5FF" strokeOpacity=".55" strokeWidth="3" />
      <ellipse cx="124" cy="138" rx="12" ry="17" fill="#0C151F" /><ellipse cx="188" cy="138" rx="12" ry="17" fill="#0C151F" />
      <circle cx="128" cy="133" r="4" fill="#F2F5F9" /><circle cx="192" cy="133" r="4" fill="#F2F5F9" />
      <path d={success > .4 ? 'M136 171Q156 190 178 171' : 'M138 172Q155 184 174 172'} fill="none" stroke="#0C151F" strokeWidth="5" strokeLinecap="round" />
      <path d="M68 165Q26 147 25 112M231 165Q274 144 274 112" fill="none" stroke="#C7F1FF" strokeOpacity=".7" strokeWidth="9" strokeLinecap="round" />
      <circle cx="25" cy="110" r="9" fill={theme.colors.success} />
    </svg>
    <div style={{position: 'absolute', left: 64, top: -62, padding: '12px 18px', borderRadius: 18, border: `1px solid ${theme.colors.accent}66`, background: '#111D28EE', color: theme.colors.text, fontSize: 31, lineHeight: 1.25, whiteSpace: 'nowrap', opacity: peek}}>{success > .4 ? '这一步完成了。' : work > .3 ? '我在看着。' : '开始工作？'}</div>
  </div>;
};

export const PetScene = () => {
  const frame = useCurrentFrame();
  return <StudyStage eyebrow="A COMMUNITY PET THAT WORKS." title={<>有它一起忙。</>} subtitle="工作有节奏，身边有回应。" footer={<><span style={{color: theme.colors.text}}>PR #61 · {petEvidence.contributor}</span><br />桌宠行为示意 · 角色资源可换 · {petEvidence.prerequisites}</>}>
    <div style={{position: 'absolute', inset: 0, background: `radial-gradient(ellipse at 55% 62%,${theme.colors.accentAlt}19,transparent 48%)`}} />
    <div style={{position: 'absolute', left: 250, top: 30, width: 590, height: 760, borderRadius: 54, border: `1px solid ${theme.colors.accent}44`, background: `linear-gradient(140deg,${theme.colors.surfaceAlt},#0A121A)`, boxShadow: '0 40px 90px #00000099'}}>
      <div style={{position: 'absolute', left: 40, top: 46, fontFamily: theme.fonts.mono, fontSize: 34, color: theme.colors.secondary}}>DSH ENGINE / WORKING</div>
      {[0,1,2,3].map((i) => <div key={i} style={{position: 'absolute', left: 48, right: 48, top: 160 + i * 108, height: 66, borderRadius: 16, border: `1px solid ${theme.colors.accent}33`, background: '#10202B', opacity: .55}}><div style={{width: `${75 - i * 9}%`, height: 7, margin: '28px 18px', background: i === 3 ? theme.colors.success : theme.colors.accent, borderRadius: 5, opacity: .55}} /></div>)}
      <div style={{position: 'absolute', left: 48, right: 48, bottom: 46, fontFamily: theme.fonts.mono, fontSize: 34, color: theme.colors.success}}>tool_call → done</div>
    </div>
    <PetActor frame={frame} />
  </StudyStage>;
};
