import {useCurrentFrame} from 'remotion';
import {theme} from '../../theme';
import {performance} from '../data';
import {progress} from '../motion';
import {StudyStage} from '../components/StudyStage';
import {WatchModel, PhoneModel} from '../components/DeviceModels';
import {CameraRig} from '../components/CameraRig';

export const DevicesScene = () => {
  const frame = useCurrentFrame();
  const close = progress(frame, 150, 170);
  const ready = frame >= 312;
  const scale = 51;
  const axisX = 185;
  const finish = (seconds: number) => axisX + seconds * scale;
  return <StudyStage eyebrow="NATIVE. EVEN ON YOUR WRIST." title={<>连手表，<br />也能跑完整引擎。</>}
    footer={<><span style={{color: theme.colors.text}}>紫光展锐手表 · {performance.watch.memoryGb} GB 内存 · 用户实测</span><br />按实测值演绎 · 时间压缩 · 不同设备实测，非同步录制</>}>
    <svg width="950" height="850" viewBox="0 0 950 850" style={{position: 'absolute', inset: 0}} data-v4-graphics="device-ground">
      <defs><radialGradient id="watch-floor"><stop stopColor={theme.colors.accent} stopOpacity=".32" /><stop offset="1" stopColor={theme.colors.accent} stopOpacity="0" /></radialGradient><filter id="watch-floor-blur"><feGaussianBlur stdDeviation="15" /></filter></defs>
      <ellipse cx="550" cy="672" rx="390" ry="97" fill="url(#watch-floor)" filter="url(#watch-floor-blur)" />
      <ellipse cx="550" cy="672" rx="335" ry="70" fill="none" stroke={theme.colors.accent} strokeOpacity=".17" />
      <ellipse cx="550" cy="672" rx="405" ry="88" fill="none" stroke={theme.colors.accent} strokeOpacity=".08" />
    </svg>
    <div style={{position: 'absolute', left: 25, top: 25, width: 400, height: 250, display: 'flex', flexDirection: 'column', gap: 10}}>
      {[{name: '旗舰', sec: performance.flagship.seconds, detail: performance.flagship.label}, {name: '中端', sec: 5.5, detail: '主流中端'}, {name: '手表', sec: performance.watch.seconds, detail: '3 GB 内存'}].map((device, index) => <div key={device.name} data-v4-device-row style={{display: 'flex', alignItems: 'center', gap: 14, opacity: index === 2 ? 1 : .7}}>
        <div style={{width: 92, flexShrink: 0, fontSize: 34, lineHeight: 1.1, whiteSpace: 'nowrap', fontWeight: index === 2 ? 700 : 400, color: index === 2 ? theme.colors.success : theme.colors.secondary}}>{device.name}</div>
        <div style={{flex: 1, minWidth: 0, display: 'flex', flexDirection: 'column', gap: 5}}>
          <div style={{fontSize: 27, color: theme.colors.muted, whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis'}}>{device.detail}</div>
          <div style={{height: 8, borderRadius: 5, background: '#182734', overflow: 'hidden'}}><div style={{height: '100%', width: `${device.sec / 13 * 100}%`, borderRadius: 5, background: index === 2 ? theme.colors.success : theme.colors.accent}} /></div>
        </div>
        <div style={{flexShrink: 0, fontFamily: theme.fonts.numeric, fontSize: 33, color: index === 2 ? theme.colors.success : theme.colors.text}}>{device.name === '中端' ? '5–6' : device.sec.toFixed(1)}s</div>
      </div>)}
    </div>
    <div style={{position: 'absolute', left: 20, top: 320, transform: 'perspective(1300px) rotateY(22deg) rotateZ(-8deg)', opacity: .34, filter: 'blur(.6px)'}}><PhoneModel width={210} height={395} muted /></div>
    <div style={{position: 'absolute', left: 190, top: 290, transform: 'perspective(1300px) rotateY(15deg) rotateZ(-7deg)', opacity: .46}}><PhoneModel width={213} height={400} muted /></div>
    <CameraRig yaw={-10 + close * 4} pitch={7 - close * 2} scale={.95 + close * .05}>
      <div style={{position: 'absolute', left: 400, top: 48, transform: 'rotateZ(-12deg)'}}><WatchModel size={440} ready={ready} progress={Math.min(1, frame / 312)} /></div>
    </CameraRig>
    <div data-v4-text="device-time-scale" style={{position: 'absolute', left: 15, top: 700, width: 930, height: 118}}>
      <div style={{position: 'absolute', left: axisX, right: 18, top: 46, height: 3, background: `linear-gradient(90deg,${theme.colors.accent},${theme.colors.success})`, opacity: .65}} />
      {[{sec:3.4,label:'3.4s',color:theme.colors.accent},{sec:5.5,label:'5–6s',color:theme.colors.accentAlt},{sec:13,label:'13s',color:theme.colors.success}].map((tick) => <div key={tick.label} style={{position:'absolute',left:finish(tick.sec),top:32,transform:'translateX(-50%)',display:'flex',flexDirection:'column',alignItems:'center',gap:8}}><div style={{width:17,height:17,borderRadius:10,background:tick.color,boxShadow:`0 0 24px ${tick.color}99`}}/><span style={{fontFamily:theme.fonts.numeric,fontSize:31,color:tick.color,whiteSpace:'nowrap'}}>{tick.label}</span></div>)}
      <div style={{position:'absolute',left:axisX,top:88,fontSize:25,color:theme.colors.muted}}>冷启动实测值 · 同一秒数比例</div>
    </div>
    <div data-v4-text="watch-hero" style={{position: 'absolute', left: 27, top: 470, display: 'flex', alignItems: 'baseline', gap: 14}}>
      <span style={{fontFamily: theme.fonts.numeric, fontSize: 150, lineHeight: 1, letterSpacing: '-.065em', fontWeight: 300, color: theme.colors.success}}>13</span>
      <span style={{fontFamily: theme.fonts.numeric, fontSize: 62, color: theme.colors.success}}>s</span>
      <span style={{fontSize: 34, lineHeight: 1.2, color: theme.colors.secondary, marginLeft: 10}}>手表启动<br />引擎就绪</span>
    </div>
  </StudyStage>;
};
