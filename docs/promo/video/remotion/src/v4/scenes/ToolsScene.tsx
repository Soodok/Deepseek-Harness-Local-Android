import {useCurrentFrame} from 'remotion';
import {theme} from '../../theme';
import {progress} from '../motion';
import {tools} from '../data';
import {StudyStage} from '../components/StudyStage';
import {PhoneModel} from '../components/DeviceModels';
import {CameraRig} from '../components/CameraRig';
import {ToolConvergence} from '../components/ToolConvergence';

export const ToolsScene = () => {
  const frame = useCurrentFrame();
  const land = progress(frame, 170, 110);
  const layers = [
    {name: 'Python', detail: 'REPL', color: theme.colors.accent, start: 55},
    {name: 'Clang', detail: 'BUILD', color: theme.colors.accentAlt, start: 105},
    {name: 'FFmpeg', detail: 'MEDIA', color: theme.colors.success, start: 155},
  ];
  const gridReady = progress(frame, 70, 145);
  return <StudyStage eyebrow="TOOLS BECOME CAPABILITIES." title={<>工具链，<br />汇入手机。</>} subtitle="把手机，变成工作站。"
    footer={<><span style={{color: theme.colors.text}}>依赖闭包 · SHA-256 · 原子发布主路径</span><br />能力生长示意 · 并非真实安装耗时</>}>
    <div style={{position: 'absolute', inset: 0, background: 'radial-gradient(ellipse at 50% 50%,#7DD3FC15,transparent 56%)'}} />
    <ToolConvergence frame={frame} width={950} height={780} />
    {/* v4.1：hero 上移到视觉舞台顶部，并去掉工具名列表 ——
        原来 left:12/top:185 的 164px「21」正好压住粒子标签（Python/FFmpeg），
        实测在成片 t=12s 处文字互相切割。工具名由粒子自己带，不必重复。 */}
    <div data-v4-tool-hero style={{position: 'absolute', left: 10, top: -10, width: 210, display: 'flex', flexDirection: 'column', gap: 4}}>
      <div style={{fontFamily: theme.fonts.numeric, fontSize: 172, lineHeight: .92, fontWeight: 300, letterSpacing: '-.07em', color: theme.colors.accent}}>21</div>
      <div style={{fontSize: 40, lineHeight: 1.15, fontWeight: 700}}>项工具</div>
      <div style={{fontSize: 36, lineHeight: 1.2, color: theme.colors.secondary}}>一键装</div>
    </div>
    <CameraRig yaw={-18 * (1 - land)} pitch={7 * (1 - land)}>
      <div style={{position: 'absolute', left: 286, top: 43}}>
        <div style={{position: 'absolute', inset: '-20px -16px', borderRadius: 60, boxShadow: `0 0 90px ${theme.colors.accent}28`, border: '1px solid #7DD3FC18'}} />
        <PhoneModel width={378} height={648}>
          <div style={{position: 'absolute', inset: '44px 25px 38px', display: 'flex', flexDirection: 'column', gap: 17}}>
            <div style={{fontFamily: theme.fonts.mono, fontSize: 36, lineHeight: 1.2, color: theme.colors.secondary}}>DSH ENGINE</div>
            <div style={{height: 3, background: `linear-gradient(90deg,${theme.colors.accent},transparent)`, marginBottom: 3}} />
            {layers.map((layer) => {
              const amount = progress(frame, layer.start, 65);
              return <div key={layer.name} data-v4-text="capability-layer" style={{position: 'relative', border: `1px solid ${layer.color}55`,
                borderRadius: 17, padding: '17px 18px', display: 'flex', flexDirection: 'column', gap: 8,
                background: `linear-gradient(120deg,${layer.color}1A,#15222F)`, opacity: .3 + amount * .7,
                transform: `translateZ(${amount * 12}px) translateY(${(1 - amount) * 22}px)`, boxShadow: `0 6px 25px ${layer.color}12`}}>
                <div style={{fontFamily: theme.fonts.numeric, fontSize: 38, lineHeight: 1.1, fontWeight: 700, color: layer.color}}>{layer.name}</div>
                <div style={{fontFamily: theme.fonts.mono, fontSize: 36, lineHeight: 1.1, color: theme.colors.text}}><span style={{opacity: .5}}>› </span>{layer.detail}</div>
              </div>;
            })}
            <div data-v4-tools-grid style={{display: 'grid', gridTemplateColumns: 'repeat(7, 18px)', gridAutoRows: 18, gap: 11, paddingTop: 7, opacity: .25 + gridReady * .75}}>
              {tools.map((tool, i) => <div key={tool.id} title={tool.name} style={{width: 18, height: 18, borderRadius: 9,
                background: i % 3 === 0 ? theme.colors.accentAlt : i % 3 === 1 ? theme.colors.success : theme.colors.accent,
                boxShadow: `0 0 9px ${i % 3 === 0 ? theme.colors.accentAlt : theme.colors.accent}66`}} />)}
            </div>
            <div style={{fontFamily: theme.fonts.mono, fontSize: 25, color: theme.colors.success}}>21 / 21 EXTENSIONS</div>
          </div>
        </PhoneModel>
      </div>
    </CameraRig>
    <div data-v4-text="tool-capacity" style={{position: 'absolute', top: 736, left: 0, width: '100%', display: 'flex', alignItems: 'center', justifyContent: 'center', gap: 22}}>
      <div style={{width: 80, height: 1, background: '#7DD3FC55'}} />
      <span style={{fontFamily: theme.fonts.mono, fontSize: 36, lineHeight: 1.2, letterSpacing: '.08em', color: theme.colors.accent}}>21 TOOLS. ONE ENGINE.</span>
      <div style={{width: 80, height: 1, background: '#7DD3FC55'}} />
    </div>
  </StudyStage>;
};
