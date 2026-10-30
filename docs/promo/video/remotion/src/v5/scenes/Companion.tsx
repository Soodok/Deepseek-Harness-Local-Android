import {AbsoluteFill, useCurrentFrame} from 'remotion';
import {Backdrop, Box, BrandMark, C, Chip, F, PetClip, Ring, Stage, useFormat} from '../components/Studio';
import {mix, phase} from '../motion';

export const PetScene = () => {
  const frame = useCurrentFrame();
  const {landscape: l} = useFormat();
  const working = frame >= 90 && frame < 210;
  const celebrating = frame >= 210;
  const clip = celebrating ? 'celebrate' : working ? 'coding' : 'idle';
  const start = celebrating ? 210 : working ? 90 : 0;
  return <Stage number="08" label="MADE BY THE COMMUNITY" accent={C.purple}
    title={<>你忙。<br/><span style={{color: C.purple}}>它也在。</span></>}
    subtitle="社区贡献的蓝发角色，陪着你的 Agent 一起工作。"
    footer={<>真实资源包角色 · 非应用录屏。<br/>需另装资源包、桥插件，并授予悬浮窗权限。</>}>
    <Box style={{left: l ? 20 : 24, top: l ? 12 : 0, width: l ? 940 : 904, height: l ? 576 : 704,
      overflow: 'hidden', borderRadius: 44, background: `radial-gradient(ellipse at 50% 47%,${C.purple}32 0%,${C.blue}12 34%,transparent 64%),linear-gradient(145deg,#1B2A3D,#101722 70%,#0F1724)`, boxShadow: '0 40px 100px #0008',
      transform: `perspective(1800px) rotateY(${mix(7, 0, phase(frame, 0, 48))}deg)`}}>
      <div style={{position: 'absolute', left: l ? 145 : 126, top: l ? 54 : 100, width: l ? 650 : 650, height: l ? 650 : 650, borderRadius: '50%', border: `1px solid ${C.blue}28`}}/>
      <div style={{position: 'absolute', left: l ? 231 : 213, top: l ? 138 : 183, width: 480, height: 480, borderRadius: '50%', border: `1px solid ${C.purple}25`}}/>
      <div style={{position: 'absolute', left: 30, top: 27, display: 'flex', alignItems: 'center', gap: 12}}>
        <div style={{width: 9, height: 9, borderRadius: '50%', background: C.purple}}/>
        <span data-v5-text style={{fontSize: l ? 22 : 26, fontFamily: F.mono, color: '#A9B8D0'}}>COMMUNITY CHARACTER / ORIGINAL ASSET</span>
      </div>
      <Box style={{left: l ? 286 : 252, top: l ? 82 : 149,
        transform: `translateY(${mix(60, 0, phase(frame, 0, 42))}px)`}}>
        <PetClip clip={clip} start={start} width={l ? 410 : 428} loop={false}/>
      </Box>
      <div style={{position: 'absolute', left: l ? 277 : 245, top: l ? 500 : 594, width: 430, height: 35,
        borderRadius: '50%', background: `${C.blue}18`, filter: 'blur(16px)', zIndex: 0}}/>
      <div data-v5-text style={{position: 'absolute', right: 29, top: l ? 88 : 102, color: '#D5DDF0', fontSize: l ? 27 : 30, background: '#18263ADD', border: `1px solid ${C.blue}35`, borderRadius: 17, padding: '13px 22px',
        transform: `translateY(${mix(12, 0, phase(frame, 7, 30))}px)`}}>{celebrating ? '任务完成。' : working ? '我也在写代码。' : '今天，也一起。'}</div>
      <div data-v5-text style={{position: 'absolute', bottom: 27, left: 30, color: '#9AAAC3', fontFamily: F.mono, fontSize: l ? 20 : 24}}>IDLE → WORKING → CELEBRATE</div>
    </Box>
    <Box style={{left: l ? 28 : 30, top: l ? 634 : 770, display: 'flex', alignItems: 'flex-start', gap: 30}}>
      <div style={{height: l ? 65 : 100, width: 4, background: C.purple}}/>
      <div>
        <div data-v5-text style={{fontSize: l ? 35 : 43, fontWeight: 700}}>@XiaoPb <span style={{fontSize: l ? 25 : 30, color: C.muted, fontWeight: 400, marginLeft: 14}}>社区贡献</span></div>
        <div data-v5-text style={{fontSize: l ? 25 : 31, color: C.muted, marginTop: 15}}>24 个 Kotlin 模块 · 动画链 / 物理 / 事件桥接</div>
      </div>
    </Box>
  </Stage>;
};

export const EndScene = () => {
  const frame = useCurrentFrame();
  const {landscape: l} = useFormat();
  return <AbsoluteFill style={{color: C.white, fontFamily: F.sans, overflow: 'hidden'}}>
    <Backdrop bright/>
    <Box style={{left: l ? 1120 : 240, top: l ? 126 : 816, opacity: .45}}><Ring size={l ? 740 : 740} progress={1}/></Box>
    <Box style={{left: l ? 1320 : 380, top: l ? 318 : 972, transform: `translateY(${mix(42, 0, phase(frame, 0, 45))}px)`}}>
      <PetClip clip="idle" width={l ? 360 : 410}/>
    </Box>
    <Box style={{left: l ? 96 : 76, top: l ? 112 : 170, display: 'flex', alignItems: 'center', gap: 24}}>
      <BrandMark size={l ? 92 : 104}/>
      <div data-v5-text style={{fontFamily: F.mono, fontSize: l ? 24 : 28, letterSpacing: '.12em', color: C.blue}}>DEEPSEEK HARNESS / ANDROID</div>
    </Box>
    <Box style={{left: l ? 96 : 76, top: l ? 300 : 400, width: l ? 1150 : 950}}>
      <div data-v5-text style={{fontSize: l ? 144 : 134, fontWeight: 700, letterSpacing: '-.075em', lineHeight: 1.05,
        transform: `translateY(${mix(36, 0, phase(frame, 0, 48))}px)`}}>DSH Mobile<span style={{color: C.blue}}>.</span></div>
      <div data-v5-text style={{fontFamily: F.chinese, fontSize: l ? 62 : 70, marginTop: 38, fontWeight: 700, letterSpacing: '-.045em', lineHeight: 1.35}}>完整 Agent。<br/><span style={{color: C.blue}}>现在，在你口袋。</span></div>
      <div style={{display: 'flex', gap: 17, marginTop: 44}}>{['No Root', 'No Termux', 'No PC'].map((text) => <Chip key={text} style={{fontSize: l ? 28 : 30}}>{text}</Chip>)}</div>
    </Box>
    <Box style={{left: l ? 96 : 76, top: l ? 796 : 1536, width: l ? 1150 : 928}}>
      <div data-v5-text style={{fontSize: l ? 30 : 35, color: C.white, fontWeight: 700, marginBottom: 18}}>开源。一起把它做大。</div>
      <div data-v5-text style={{fontSize: l ? 26 : 29, fontFamily: F.mono, color: C.blue, lineHeight: 1.6}}>github.com/Soodok/<br/>Deepseek-Harness-Local-Android</div>
    </Box>
    <Box data-v5-text style={{left: l ? 96 : 76, top: l ? 960 : 1770, fontSize: l ? 24 : 28, color: C.muted, lineHeight: 1.45}}>社区移植，与 DeepSeek 官方无隶属关系。<br/>模型请求使用你配置的 API。</Box>
    <Box style={{right: l ? 96 : 76, top: l ? 96 : 110, color: C.muted, fontFamily: F.mono, fontSize: 26}}>OPEN SOURCE / MADE TO GROW</Box>
  </AbsoluteFill>;
};
