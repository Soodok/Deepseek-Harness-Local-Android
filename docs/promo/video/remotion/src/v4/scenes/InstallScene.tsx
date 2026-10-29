import {useCurrentFrame} from 'remotion';
import {theme} from '../../theme';
import {progress} from '../motion';
import {StudyStage} from '../components/StudyStage';
import {RoutedParticles} from '../components/RoutedParticles';

export const InstallScene = () => {
  const frame = useCurrentFrame();
  const stages = ['依赖闭包', 'SHA-256', 'shebang 修复', 'rename 发布'];
  return <StudyStage eyebrow="INSTALL. THEN PUBLISH." title={<>装进去，<br />也装对。</>} subtitle="失败不报成功。" footer={<><span style={{color: theme.colors.text}}>临时目录准备 · rename 发布主路径</span><br />不完整不报成功；运行时 ELF 架构断言独立校验入口 node</>} accent={theme.colors.accentAlt}>
    <div style={{position: 'absolute', inset: 0, display: 'flex', flexDirection: 'column', justifyContent: 'center', gap: 44}}>
      {stages.map((stage, index) => {
        const on = progress(frame, index * 24, 32);
        return <div key={stage} data-v4-text="install-stage" style={{position: 'relative', display: 'flex', alignItems: 'center', gap: 22, opacity: .3 + on * .7,
          transform: `translateX(${(1 - on) * 32}px)`}}>
          <div style={{width: 84, height: 84, borderRadius: 22, border: `2px solid ${index === 3 ? theme.colors.success : theme.colors.accentAlt}`, display: 'flex', alignItems: 'center', justifyContent: 'center', color: index === 3 ? theme.colors.success : theme.colors.accentAlt, fontFamily: theme.fonts.mono, fontSize: 40}}>{String(index + 1).padStart(2, '0')}</div>
          <div style={{flex: 1, height: 118, borderRadius: 22, border: `1px solid ${theme.colors.accentAlt}55`, background: `linear-gradient(120deg,${theme.colors.accentAlt}18,${theme.colors.surfaceAlt})`, display: 'flex', alignItems: 'center', padding: '0 28px', justifyContent: 'space-between'}}>
            <span style={{fontSize: 50, fontWeight: 700}}>{stage}</span>
            <span style={{fontFamily: theme.fonts.mono, fontSize: 38, color: index === 3 ? theme.colors.success : theme.colors.accent}}>{index === 0 ? '21→closure' : index === 1 ? 'SHA OK' : index === 2 ? '#!/fixed' : 'ATOMIC'}</span>
          </div>
          {index < stages.length - 1 && <div style={{position: 'absolute', left: 41, top: 88, height: 52, width: 2, background: theme.colors.accentAlt, opacity: on * .55}} />}
        </div>;
      })}
    </div>
    <RoutedParticles frame={frame} width={950} height={850} duration={38} routes={[
      {id: 'install-a', delay: 0, color: theme.colors.accentAlt, points: [{x: 45,y: 100},{x: 300,y: 240},{x: 590,y: 305},{x: 840,y: 350}]},
      {id: 'install-b', delay: 24, color: theme.colors.success, points: [{x: 50,y: 600},{x: 320,y: 480},{x: 640,y: 535},{x: 850,y: 580}]},
    ]} />
  </StudyStage>;
};
