import {useCurrentFrame} from 'remotion';
import {Box, C, Chip, F, Metric, Panel, Ring, Stage, useFormat} from '../components/Studio';
import {PickupSlot} from '../components/PickupSlot';
import {facts} from '../data';
import {key, mix, phase} from '../motion';

export const AccessibilityScene = () => {
  const frame = useCurrentFrame();
  const {landscape: l} = useFormat();
  const active = Math.min(2, Math.floor(Math.max(0, frame - 20) / 42));
  return <Stage number="04" label="AI WITH EYES & HANDS" accent={C.green}
    title={<>AI，<br/><span style={{color: C.green}}>直接操作手机。</span></>}
    subtitle="读屏 → 决策 → 执行 → 验证。"
    footer={<>开启无障碍服务后可用 · 实测单步约 1.45s。<br/>含决策、执行、验证；耗时随页面变化。</>}>
    <Box style={{left: l ? 16 : 36, top: l ? 2 : 0}}>
      <Metric value="1.45" unit={l ? 's' : 's / 步'} size={l ? 132 : 176} accent={C.green} label="本地完成一轮闭环"/>
    </Box>
    <Box style={{left: l ? 16 : 36, top: l ? 278 : 300, display: 'flex', flexDirection: l ? 'column' : 'row', gap: l ? 22 : 18}}>
      {['找到目标', '执行操作', '验中结果'].map((text, i) => <Panel key={text} accent={i === active ? C.green : C.blue}
        style={{width: l ? 270 : 276, height: l ? 100 : 110, padding: '20px 22px', display: 'flex', alignItems: 'center', gap: 18,
          transform: `translateX(${mix(-28, 0, phase(frame, i * 8, 40))}px)`, borderColor: i <= active ? `${C.green}90` : C.line}}>
        <div style={{color: i <= active ? C.green : C.muted, fontFamily: F.mono, fontSize: 27}}>0{i + 1}</div>
        <div data-v5-text style={{fontSize: l ? 30 : 32, color: i <= active ? C.white : C.muted, fontWeight: 700}}>{text}</div>
      </Panel>)}
    </Box>
    <Box style={{left: l ? 374 : 36, top: l ? 34 : 476, transform: `translateY(${mix(42, 0, phase(frame, 12, 48))}px)`}}>
      <PickupSlot hint="AI 打开设置并完成一次操作" seconds={8} width={l ? 612 : 880} height={l ? 642 : 450}/>
    </Box>
  </Stage>;
};

export const OCRScene = () => {
  const frame = useCurrentFrame();
  const {landscape: l} = useFormat();
  const collapse = phase(frame, 15, 85);
  return <Stage number="05" label="SEE. UNDERSTAND. VERIFY." accent={C.blue}
    title={<>看得更快。<br/><span style={{color: C.blue}}>验得更准。</span></>}
    subtitle="节点树、OCR、图标识别，多层感知。"
    footer={<>OCR 链自身优化实测 · {facts.accessibility.ocrBefore}ms → {facts.accessibility.ocrAfter}ms。<br/>帧差用于验证变化，无法替代任意页面的语义判断。</>}>
    <Box style={{left: l ? 16 : 20, top: l ? 8 : 10, width: l ? 956 : 912}}>
      <Panel style={{height: l ? 540 : 610, padding: l ? '40px 42px' : '50px 42px'}}>
        <div data-v5-text style={{fontFamily: F.mono, fontSize: l ? 24 : 28, letterSpacing: '.1em', color: C.blue}}>OCR / PERFORMANCE</div>
        <div data-v5-text style={{fontFamily: F.numeric, fontWeight: 700, fontSize: l ? 124 : 142, letterSpacing: '-.065em', color: C.blue, lineHeight: 1.1, marginTop: 28}}>326–393<span style={{fontSize: 38, fontWeight: 400, marginLeft: 12}}>ms</span></div>
        <div data-v5-text style={{fontSize: 30, color: C.muted, marginTop: 12}}>截图节流 · 内容裁剪 · 单帧复用</div>
        <div style={{marginTop: 54}}>
          <div data-v5-text style={{display: 'flex', justifyContent: 'space-between', fontSize: 27, color: C.muted, marginBottom: 15}}><span>优化前</span><span>1370–1588 ms</span></div>
          <div style={{height: 18, borderRadius: 6, background: '#243347'}}><div style={{width: '100%', height: 18, borderRadius: 6, background: '#7185A1', opacity: .7}}/></div>
          <div data-v5-text style={{display: 'flex', justifyContent: 'space-between', fontSize: 27, color: C.blue, marginTop: 24, marginBottom: 15}}><span>优化后</span><span>326–393 ms</span></div>
          <div style={{height: 18, borderRadius: 6, background: '#243347'}}><div style={{width: `${mix(1, .2475, collapse) * 100}%`, height: 18, borderRadius: 6, background: C.blue, boxShadow: `0 0 28px ${C.blue}40`}}/></div>
        </div>
      </Panel>
    </Box>
    <Box style={{left: l ? 18 : 22, top: l ? 598 : 694, display: 'flex', gap: l ? 24 : 18}}>
      {['无障碍树', 'OCR / 图标', '目标验证'].map((text, i) => <Chip key={text} accent={i === 2 ? C.green : C.blue} style={{fontSize: l ? 28 : 30, padding: l ? '24px 29px' : '30px 31px', transform: `translateY(${mix(25, 0, phase(frame, 15 + i * 6, 35))}px)`}}>{text}</Chip>)}
    </Box>
    {!l && <Box data-v5-text style={{left: 24, top: 858, fontSize: 36, color: C.muted}}>点下去，还要知道有没有做对。</Box>}
  </Stage>;
};
