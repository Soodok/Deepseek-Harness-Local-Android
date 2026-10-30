import {useCurrentFrame} from 'remotion';
import {Box, C, Chip, F, Metric, Panel, Stage, useFormat} from '../components/Studio';
import {PickupSlot} from '../components/PickupSlot';
import {facts} from '../data';
import {mix, phase} from '../motion';

export const WebUIScene = () => {
  const frame = useCurrentFrame();
  const {landscape: l} = useFormat();
  return <Stage number="06" label="ONE ENGINE. MORE ROOM." accent={C.purple}
    title={<>大屏编辑。<br/><span style={{color: C.purple}}>小屏执行。</span></>}
    subtitle="手机上的引擎，展开到电脑浏览器。"
    footer={<>同一局域网 · 开启后用验证码配对。<br/>引擎仍在手机运行，电脑无需另装引擎。</>}>
    <Box style={{left: l ? 8 : 18, top: l ? 16 : 10, transform: `perspective(1800px) rotateY(${mix(9, 0, phase(frame, 0, 60))}deg) translateY(${mix(55, 0, phase(frame, 0, 60))}px)`}}>
      <div style={{width: l ? 982 : 916, height: l ? 548 : 578, borderRadius: '24px 24px 12px 12px', background: 'linear-gradient(135deg,#7A8EA7,#26394F 12%,#101D2D 70%,#71869D)', padding: 10, boxShadow: '0 32px 80px #0009'}}>
        <div style={{height: 44, background: '#152237', display: 'flex', alignItems: 'center', padding: '0px 17px', borderRadius: '17px 17px 0px 0px', gap: 9}}>
          {[C.blue, C.purple, C.green].map((color) => <div key={color} style={{width: 8, height: 8, borderRadius: '50%', background: color, opacity: .75}}/>)}
          <div data-v5-text style={{fontFamily: F.mono, color: C.muted, fontSize: 20, marginLeft: 20}}>DSH Mobile / LAN Web UI</div>
        </div>
        <PickupSlot hint="电脑 Web UI 编辑，手机同步执行" seconds={8} width={l ? 962 : 896} height={l ? 484 : 514} style={{borderRadius: '0px 0px 10px 10px'}}/>
      </div>
      <div style={{height: 24, width: l ? 1000 : 934, marginLeft: -9, borderRadius: '0px 0px 70px 70px', background: 'linear-gradient(180deg,#94A7BD,#2B3E57)', boxShadow: '0 28px 55px #0009'}}/>
    </Box>
    <svg width={l ? 1008 : 952} height={l ? 744 : 970} style={{position: 'absolute', inset: 0, pointerEvents: 'none'}} aria-hidden="true">
      <path d={l ? 'M520 606V650H310' : 'M484 646V732H252'} fill="none" stroke={C.purple} strokeWidth="2" opacity=".45"/>
      <circle r="6" fill={C.purple} cx={l ? 520 : 484} cy={(l ? 606 : 646) + (frame % 80) / 80 * (l ? 44 : 86)} />
    </svg>
    <Box style={{left: l ? 24 : 22, top: l ? 658 : 758, display: 'flex', gap: 18, alignItems: 'center'}}>
      <Chip accent={C.purple} style={{fontSize: l ? 27 : 29}}>同一会话</Chip>
      <div style={{height: 1, width: l ? 120 : 170, background: `${C.purple}60`}}/>
      <Chip accent={C.green} style={{fontSize: l ? 27 : 29}}>手机执行</Chip>
    </Box>
    {!l && <Box data-v5-text style={{left: 24, top: 892, fontSize: 34, color: C.muted}}>代码与任务，在大屏从容展开。</Box>}
  </Stage>;
};

export const SizeScene = () => {
  const frame = useCurrentFrame();
  const {landscape: l} = useFormat();
  return <Stage number="07" label="PACK LIGHT. GO FURTHER." accent={C.blue}
    title={<>卸下运行时，<br/><span style={{color: C.blue}}>只剩二十几兆。</span></>}
    subtitle="简单包轻装下载，运行时首启获取。"
    footer={<>安装包口径 · v1.4.36 时期版本快照。<br/>简单包首次总下载约 152MB；完整包 149MB，运行时已内置。</>}>
    <Box style={{left: l ? 20 : 22, top: l ? 0 : 0}}>
      <Metric value="24" unit="MB" size={l ? 170 : 204} label="简单包 / 不含运行时"/>
    </Box>
    <Box style={{left: l ? 20 : 22, top: l ? 274 : 324}}>
      <Panel style={{width: l ? 352 : 900, height: l ? 242 : 170, padding: '28px 28px'}}>
        <div data-v5-text style={{fontSize: l ? 26 : 30, color: C.muted}}>首启另下载运行时</div>
        <div data-v5-text style={{fontSize: l ? 32 : 46, color: C.blue, fontWeight: 700, letterSpacing: '-.045em', marginTop: 18, whiteSpace: 'nowrap'}}>24 + 128 ≈ 152<span style={{fontSize: l ? 22 : 29, fontWeight: 400, marginLeft: 8}}>MB</span></div>
        {l && <div data-v5-text style={{fontSize: 24, color: C.muted, marginTop: 18}}>首次可用总下载量</div>}
      </Panel>
    </Box>
    <Box style={{left: l ? 426 : 24, top: l ? 24 : 540, width: l ? 544 : 896}}>
      <div data-v5-text style={{fontFamily: F.mono, color: C.muted, fontSize: l ? 23 : 27, letterSpacing: '.08em', marginBottom: 22}}>同类产品安装包 / MB</div>
      {facts.size.competitors.map((row, i) => <div key={row.name} style={{marginBottom: l ? 24 : 22}}>
        <div data-v5-text style={{display: 'flex', justifyContent: 'space-between', fontSize: l ? 27 : 29, lineHeight: 1.2, marginBottom: 11}}>
          <span style={{color: C.muted}}>{row.name}<span style={{fontSize: l ? 17 : 20, marginLeft: 10, color: '#7F91A9'}}>{row.version}</span></span>
          <span style={{fontFamily: F.numeric, color: C.white}}>{row.mb}</span>
        </div>
        <div style={{width: l ? 544 : 896, height: 12, background: '#233347', borderRadius: 4}}>
          <div style={{width: (l ? 544 : 896) * row.mb / 392 * mix(.1, 1, phase(frame, i * 4, 52)), height: 12, borderRadius: 4, background: i > 2 ? '#8F9EB7' : '#5E7998'}}/>
        </div>
      </div>)}
      <div data-v5-text style={{fontSize: l ? 24 : 27, color: C.muted, lineHeight: 1.4, marginTop: 24}}>不同路线，功能与运行时配置各异。</div>
    </Box>
  </Stage>;
};
