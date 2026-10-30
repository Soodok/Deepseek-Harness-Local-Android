import {useCurrentFrame} from 'remotion';
import {theme} from '../../theme';
import {facts, copy} from '../../data';
import {StudyStage} from '../components/StudyStage';
import {RoutedParticles} from '../components/RoutedParticles';

export const NativeScene = () => {
  const frame = useCurrentFrame();
  const on = Math.min(1, frame / 26);
  const cards = [{title: '真 PTY', value: `${facts.engineering.ptyLines} 行 C`, color: theme.colors.accent}, {title: '自带 OCR', value: 'MLKit', color: theme.colors.accentAlt}, {title: '内置 SSH', value: 'ssh2', color: theme.colors.success}];
  return <StudyStage eyebrow="NATIVE CONNECTIONS" title={<>引擎相同。<br />Android 接得住。</>} footer={<><span style={{color: theme.colors.text}}>我们的实现覆盖 {facts.shell.coverage} 项 / 抽查竞品 {facts.shell.sampledCoverage}。</span><br />{copy.shell.lowerBound}</>}>
    <div style={{position: 'absolute', inset: 0, display: 'flex', flexDirection: 'column', gap: 30, justifyContent: 'center'}}>
      <div style={{display: 'flex', gap: 16}}>{cards.map((card) => <div key={card.title} data-v4-text="native-card" style={{flex: 1, minWidth: 0, height: 150, borderRadius: 22, border: `2px solid ${card.color}55`, background: theme.colors.surfaceAlt, padding: '22px 20px', boxSizing: 'border-box', opacity: .55 + on * .45, transform: `translateY(${(1 - on) * 22}px)`}}><div style={{fontSize: 38, fontWeight: 700, color: theme.colors.text}}>{card.title}</div><div style={{fontFamily: theme.fonts.mono, fontSize: 34, color: card.color, marginTop: 14}}>{card.value}</div></div>)}</div>
      <div style={{height: 300, borderRadius: 28, border: `1px solid ${theme.colors.accent}55`, background: `linear-gradient(120deg,${theme.colors.accent}10,${theme.colors.surfaceAlt})`, padding: 34, display: 'flex', flexDirection: 'column', justifyContent: 'center', gap: 22, boxShadow: `0 0 65px ${theme.colors.accent}12`}}><div style={{fontFamily: theme.fonts.mono, fontSize: 38, color: theme.colors.accent}}>OFFICIAL ENGINE → NATIVE BRIDGES</div><div style={{height: 7, borderRadius: 5, background: `linear-gradient(90deg,${theme.colors.accent},${theme.colors.success})`, transformOrigin: 'left', scale: `${on} 1`}} /><div style={{fontSize: 38, color: theme.colors.secondary}}>真终端 · 读屏 · 远程</div></div>
    </div>
    <RoutedParticles frame={frame} width={950} height={850} duration={42} routes={cards.map((card, i) => ({id: card.title, delay: i * 12, color: card.color, points: [{x: 40,y: 120 + i * 210},{x: 300,y: 120 + i * 180},{x: 620,y: 310},{x: 880,y: 430}]}))} />
  </StudyStage>;
};
