import {useCurrentFrame} from 'remotion';
import {theme} from '../../theme';
import {accessibility} from '../data';
import {StudyStage} from '../components/StudyStage';
import {RoutedParticles} from '../components/RoutedParticles';

export const PerceptionScene = () => {
  const frame = useCurrentFrame();
  const cut = frame > 118;
  const layers = [{name: '无障碍树', color: theme.colors.accent}, {name: '视觉追踪 / OCR', color: theme.colors.accentAlt}, {name: '帧差验证', color: theme.colors.success}];
  return <StudyStage eyebrow="FALLBACK IS A FEATURE." title={<>看不见，<br />也会换路。</>} subtitle="一层断了，下一层立刻接上。" footer={<><span style={{color: theme.colors.text}}>OCR {accessibility.ocrMs}ms · 此前 {accessibility.previousOcrMs}ms · 约 4×</span><br />机制示意 · 帧差负责验证变化，不替代语义判断</>} accent={theme.colors.accentAlt}>
    <div style={{position: 'absolute', inset: 0, display: 'flex', flexDirection: 'column', justifyContent: 'center', gap: 28}}>
      {layers.map((layer, index) => {
        const on = Math.max(0, Math.min(1, (frame - index * 28) / 30));
        const active = index === (cut ? 2 : 0);
        return <div key={layer.name} data-v4-text="perception-layer" style={{height: 112, borderRadius: 24, border: `2px solid ${layer.color}${active ? 'CC' : '35'}`,
          background: active ? `linear-gradient(110deg,${layer.color}30,${theme.colors.surfaceAlt})` : theme.colors.surfaceAlt, display: 'flex', alignItems: 'center', gap: 24, padding: '0 30px', opacity: .35 + on * .65,
          transform: `translateX(${(1 - on) * 30}px) scale(${active ? 1.01 : 1})`, boxShadow: active ? `0 0 40px ${layer.color}22` : 'none'}}>
          <span style={{fontFamily: theme.fonts.mono, fontSize: 36, color: layer.color}}>0{index + 1}</span><span style={{fontSize: 42, fontWeight: 700}}>{layer.name}</span><span style={{marginLeft: 'auto', fontFamily: theme.fonts.mono, fontSize: 34, color: active ? layer.color : theme.colors.muted}}>{active ? 'CONNECTED' : 'STANDBY'}</span>
        </div>;
      })}
    </div>
    <RoutedParticles frame={frame} width={950} height={850} duration={70} routes={[
      {id: 'sense-a', delay: 5, color: theme.colors.accent, points: [{x: 100,y: 360},{x: 270,y: 270},{x: 580,y: 270},{x: 840,y: 360}]},
      {id: 'sense-b', delay: cut ? 94 : 160, color: theme.colors.success, points: [{x: 100,y: 480},{x: 300,y: 620},{x: 610,y: 580},{x: 840,y: 480}]},
    ]} />
  </StudyStage>;
};
