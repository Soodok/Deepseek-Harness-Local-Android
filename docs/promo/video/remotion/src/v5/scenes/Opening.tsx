import {AbsoluteFill, useCurrentFrame} from 'remotion';
import {Backdrop, Box, BrandMark, C, Chip, F, Phone, Ring, Stage, Panel, useFormat} from '../components/Studio';
import {facts} from '../data';
import {key, mix, phase} from '../motion';

export const IntroScene = () => {
  const frame = useCurrentFrame();
  const {landscape: l} = useFormat();
  const rise = phase(frame, 0, 60);
  return <AbsoluteFill style={{fontFamily: F.sans, color: C.white, overflow: 'hidden'}}>
    <Backdrop bright/>
    <Box style={{left: l ? 96 : 76, top: l ? 68 : 102, display: 'flex', alignItems: 'center', gap: 18}}>
      <BrandMark size={64}/><span style={{fontSize: 34, fontWeight: 700}}>DSH Mobile</span>
    </Box>
    <Box style={{left: l ? 96 : 76, top: l ? 258 : 326, width: l ? 900 : 950, zIndex: 3}}>
      <div data-v5-text style={{fontFamily: F.mono, color: C.blue, fontSize: l ? 27 : 31, letterSpacing: '.11em', marginBottom: 30}}>DEEPSEEK HARNESS / FOR ANDROID</div>
      <div data-v5-text style={{fontFamily: F.chinese, fontSize: l ? 112 : 110, lineHeight: 1.17, fontWeight: 700, letterSpacing: '-.06em', transform: `translateY(${mix(18, 0, rise)}px)`}}>完整 Agent，<br/><span style={{color: C.blue}}>装进口袋。</span></div>
      <div data-v5-text style={{fontSize: l ? 34 : 37, color: C.muted, marginTop: 32}}>在 Android 上原生运行。</div>
    </Box>
    <Box style={{left: l ? 1040 : 160, top: l ? 132 : 780, transform: `rotate(${mix(5, -7, rise)}deg) translateY(${mix(24, 0, rise)}px)`, zIndex: 1}}>
      <div style={{position: 'absolute', left: -145, top: 80, opacity: .32}}><Ring size={l ? 800 : 800} progress={phase(frame, 0, 80)}/></div>
      <Phone file="04-extensions.png" width={l ? 395 : 414} height={l ? 790 : 770} style={{marginLeft: l ? 138 : 330, transform: `perspective(1600px) rotateY(${mix(-22, -8, rise)}deg)`, transformOrigin: 'center'}}/>
    </Box>
    <Box style={{left: l ? 945 : 76, top: l ? 710 : 1120, zIndex: 4, transform: `translateX(${mix(-35, 0, phase(frame, 12, 45))}px)`}}>
      <Panel style={{width: l ? 360 : 418, padding: l ? '30px 32px' : '40px 36px', backdropFilter: 'blur(12px)'}}>
        <div data-v5-text style={{fontFamily: F.mono, color: C.blue, fontSize: 24, marginBottom: 12}}>BUILT FOR YOUR PHONE</div>
        <div data-v5-text style={{fontSize: l ? 70 : 80, fontWeight: 700, lineHeight: 1.05, letterSpacing: '-.05em'}}>21<span style={{fontSize: 30, marginLeft: 14, fontWeight: 400}}>项环境扩展</span></div>
        <div style={{width: 76, height: 3, background: C.blue, marginTop: 24}}/>
      </Panel>
    </Box>
    <Box style={{left: l ? 96 : 76, top: l ? 840 : 1638, display: 'flex', gap: 18, zIndex: 5}}>
      {['No Root', 'No Termux', 'No PC'].map((text) => <Chip key={text} style={{fontSize: l ? 27 : 29}}>{text}</Chip>)}
    </Box>
    <Box data-v5-text style={{left: l ? 96 : 76, top: l ? 974 : 1770, color: C.muted, fontSize: l ? 25 : 28}}>社区移植 · 模型请求使用你配置的 API</Box>
    <Box style={{right: l ? 96 : 76, top: l ? 92 : 124, fontFamily: F.mono, fontSize: 25, color: C.muted}}>OPEN SOURCE</Box>
  </AbsoluteFill>;
};

const Watch = ({size, frame}: {size: number; frame: number}) => {
  const progress = phase(frame, 12, 260);
  return <div style={{position: 'relative', width: size, height: size + 160}}>
    <div style={{position: 'absolute', width: size * .37, left: size * .315, top: -42, height: size + 120, borderRadius: 70, background: 'repeating-linear-gradient(0deg,#162338 0px,#162338 9px,#0D1829 10px,#0D1829 15px)', boxShadow: 'inset 0 0 0 3px #6D90B625'}}/>
    <div style={{position: 'absolute', top: 20, width: size, height: size, borderRadius: '50%', padding: 12, background: 'linear-gradient(135deg,#A7BDD4,#263C56 25%,#0E1725 57%,#8BA3BF)', boxShadow: '0 40px 90px #0009'}}>
      <div style={{position: 'absolute', right: -9, top: size * .44, width: 15, height: 52, background: 'linear-gradient(90deg,#425B74,#92ADC6)', borderRadius: 5}}/>
      <div style={{position: 'relative', width: size - 24, height: size - 24, background: 'radial-gradient(ellipse at 40% 30%,#142B43,#080E16 76%)', borderRadius: '50%', boxShadow: 'inset 0 0 0 3px #03070B'}}>
        <Ring size={size - 24} progress={progress} accent={frame > 272 ? C.green : C.blue}>
          <div data-v5-text style={{fontFamily: F.mono, fontSize: 23, color: frame > 272 ? C.green : C.blue, marginBottom: 20}}>ENGINE {frame > 272 ? 'READY' : 'STARTING'}</div>
          <div data-v5-text style={{fontFamily: F.numeric, fontSize: 148, fontWeight: 700, letterSpacing: '-.07em', lineHeight: 1}}>13<span style={{fontSize: 46, fontWeight: 300, marginLeft: 26}}>s</span></div>
          <div data-v5-text style={{marginTop: 20, color: C.muted, fontSize: 28}}>冷启动实测</div>
        </Ring>
      </div>
    </div>
  </div>;
};

const SpeedCard = ({value, label, detail, width, frame, delay}: {value: string; label: string; detail?: string; width: number; frame: number; delay: number}) =>
  <Panel style={{width, height: 220, padding: '25px 28px', position: 'relative', transform: `translateY(${mix(32, 0, phase(frame, delay, 40))}px)`}}>
    <div data-v5-text style={{fontSize: 28, color: C.muted}}>{label}</div>
    <div data-v5-text style={{fontFamily: F.numeric, color: C.blue, fontWeight: 700, fontSize: 76, letterSpacing: '-.055em', lineHeight: 1.1, marginTop: 14}}>{value}<span style={{fontSize: 30, fontWeight: 400, marginLeft: 12}}>s</span></div>
    {detail && <div data-v5-text style={{fontSize: 22, color: C.muted, marginTop: 8}}>{detail}</div>}
  </Panel>;

export const PerformanceScene = () => {
  const frame = useCurrentFrame();
  const {landscape: l} = useFormat();
  return <Stage number="01" label="NATIVE PERFORMANCE" title={<>小到手表。<br/><span style={{color: C.blue}}>完整开跑。</span></>}
    subtitle="bionic 原生直跑，没有中间层。"
    footer={<>真机实测 · {l ? '不同设备，结果随环境变化；表盘为示意。' : <>不同设备，结果随环境变化。<br/>表盘为示意，按实测值演绎。</>}</>}>
    <Box style={{left: l ? 40 : 218, top: l ? 24 : 8, transform: `perspective(1800px) rotateY(${key(frame, [0, 55, 300, 419], [-16, -3, 0, 0])}deg) rotateZ(${key(frame, [0, 70], [-8, 0])}deg) scale(${key(frame, [0, 75], [.88, 1])})`}}>
      <Watch size={l ? 510 : 520} frame={frame}/>
    </Box>
    <Box style={{left: l ? 44 : 72, top: l ? 628 : 638, width: l ? 520 : 808, textAlign: 'center'}}>
      <div data-v5-text style={{fontSize: l ? 34 : 38, fontWeight: 700}}>{facts.performance.watch.label} <span style={{color: C.blue}}>3GB</span></div>
      <div data-v5-text style={{fontSize: 26, color: C.muted, marginTop: 12}}>完整 Agent 引擎，也能启动。</div>
    </Box>
    <Box style={{left: l ? 640 : 18, top: l ? 80 : 790, display: 'flex', flexDirection: l ? 'column' : 'row', gap: 24}}>
      <SpeedCard frame={frame} delay={15} value="3.4" label="旗舰真机" detail="一加 15T · Android 16" width={l ? 330 : 446}/>
      <SpeedCard frame={frame} delay={28} value="5–6" label="主流中端" detail="冷启动到引擎就绪" width={l ? 330 : 446}/>
    </Box>
  </Stage>;
};
