import {useCurrentFrame} from 'remotion';
import {Box, C, Chip, F, Metric, Panel, Phone, ScreenPhoto, Stage, useFormat} from '../components/Studio';
import {PickupSlot} from '../components/PickupSlot';
import {facts, tools} from '../data';
import {key, mix, phase} from '../motion';

export const ExtensionsScene = () => {
  const frame = useCurrentFrame();
  const {landscape: l} = useFormat();
  return <Stage number="02" label="BUILD YOUR TOOLCHAIN" title={<>能力，<br/><span style={{color: C.blue}}>按需生长。</span></>}
    subtitle="Python、Go、Rust、JDK、FFmpeg、OpenSSH…"
    footer={<>真实扩展中心界面 · 环境扩展按需安装。<br/>{l ? '语言、编译、媒体与远程工具，接入同一个 Agent。' : '语言 · 编译 · 媒体 · 远程工具'}</>}>
    <Box style={{left: l ? 12 : 22, top: l ? 12 : 0}}>
      <Metric value="21" unit="项" size={l ? 164 : 170} label="环境扩展，一键安装。"/>
    </Box>
    <Box style={{left: l ? 602 : 524, top: l ? 32 : 140,
      transform: `perspective(1600px) rotateY(${key(frame, [0, 60, 389], [-24, -7, -3])}deg) rotateZ(${key(frame, [0, 60], [8, 3])}deg) translateY(${mix(64, 0, phase(frame, 0, 65))}px)`}}>
      <Phone file="04-extensions.png" width={l ? 358 : 386} height={l ? 682 : 786} />
    </Box>
    {tools.map((tool, i) => {
      const col = i % 2, row = Math.floor(i / 2);
      const arrive = phase(frame, 12 + i * 5, 48);
      return <Box key={tool.name} style={{left: (l ? 12 : 22) + col * (l ? 254 : 236), top: (l ? 254 : 310) + row * (l ? 146 : 184),
        transform: `translate(${mix(42 + row * 8, 0, arrive)}px,${mix(45, 0, arrive)}px) scale(${mix(.88, 1, arrive)})`, opacity: mix(.3, 1, arrive)}}>
        <Panel accent={tool.color} style={{width: l ? 226 : 212, height: l ? 126 : 154, padding: '20px 21px'}}>
          <div data-v5-text style={{color: tool.color, fontFamily: F.mono, fontSize: 38, fontWeight: 700}}>{tool.mark}</div>
          <div data-v5-text style={{fontSize: l ? 26 : 28, marginTop: l ? 10 : 20, fontWeight: 700}}>{tool.name}</div>
        </Panel>
      </Box>;
    })}
    <svg width={l ? 1008 : 952} height={l ? 744 : 970} style={{position: 'absolute', inset: 0, zIndex: -1}} aria-hidden="true">
      {tools.map((tool, i) => <path key={i} d={l ? `M500 ${280 + i * 60} C560 ${280 + i * 60} 560 380 615 380` : `M480 ${350 + i * 85} C510 ${350 + i * 85} 490 600 530 600`}
        fill="none" stroke={tool.color} strokeWidth="1.5" opacity={.1 + .22 * phase(frame, 35 + i * 7, 35)}/>)}
    </svg>
  </Stage>;
};

export const PluginsScene = () => {
  const frame = useCurrentFrame();
  const {landscape: l} = useFormat();
  return <Stage number="03" label="AN OPEN ECOSYSTEM" accent={C.purple}
    title={<>你的工具，<br/><span style={{color: C.purple}}>还在变多。</span></>}
    subtitle="社区插件，搜索、安装、卸载。"
    footer={<>内置目录快照 · {facts.plugins} 个插件 / {facts.categories} 个分类。<br/>目录数量不代表全部插件兼容，安装前查看兼容性。</>}>
    <Box style={{left: 24, top: l ? 10 : 4, display: 'flex', alignItems: 'flex-end', gap: 32}}>
      <Metric value="3540" unit="个" size={l ? 148 : 166} accent={C.purple}/>
      <Chip accent={C.purple} style={{marginBottom: 8}}>23 分类</Chip>
    </Box>
    <Box style={{left: l ? 12 : 20, top: l ? 246 : 250,
      transform: `perspective(1400px) rotateY(${mix(-13, 0, phase(frame, 0, 55))}deg) translateY(${mix(45, 0, phase(frame, 0, 55))}px)`}}>
      <Panel accent={C.purple} style={{padding: 9, borderRadius: 30}}>
        <ScreenPhoto file="05-plugins.png" width={l ? 394 : 388} height={l ? 452 : 676} cropTop={100} pan={key(frame, [0, 180, 269], [0, 170, 240])}/>
      </Panel>
    </Box>
    <Box style={{left: l ? 454 : 444, top: l ? 230 : 332, transform: `translateY(${mix(45, 0, phase(frame, 10, 50))}px)`}}>
      <PickupSlot hint="插件市场安装一个插件" seconds={8} width={l ? 532 : 486} height={l ? 468 : 508}/>
    </Box>
  </Stage>;
};
