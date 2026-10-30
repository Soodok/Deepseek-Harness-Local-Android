import {useCurrentFrame} from 'remotion';
import {theme} from '../../theme';
import {webuiEvidence} from '../data';
import {progress} from '../motion';
import {StudyStage} from '../components/StudyStage';
import {PhoneModel} from '../components/DeviceModels';
import {CameraRig} from '../components/CameraRig';

export const WebUIScene = () => {
  const frame = useCurrentFrame();
  const bridge = progress(frame, 22, 105);
  return <StudyStage eyebrow="ONE ENGINE. TWO SCREENS." title={<>大屏编辑，<br />小屏执行。</>} subtitle="手机里的引擎，电脑浏览器直接打开。" footer={<><span style={{color: theme.colors.text}}>局域网反向代理 · {webuiEvidence.port} · 每次开启 {webuiEvidence.codeDigits} 位验证码</span><br />同一局域网 · 浏览器示意 · 不暴露真实验证码</>}>
    <div style={{position: 'absolute', inset: 0}}>
      <CameraRig yaw={-8 + bridge * 8} pitch={4 - bridge * 4} scale={.95 + bridge * .05}>
        <div style={{position: 'absolute', left: 0, top: 185, width: 430, height: 300, borderRadius: 18, padding: 12, background: 'linear-gradient(135deg,#66829A,#152834)', boxShadow: '0 28px 45px #00000088'}}>
          <div style={{height: '100%', borderRadius: 10, background: '#081019', border: '1px solid #92B9CF55', padding: 22, boxSizing: 'border-box'}}>
            <div style={{fontFamily: theme.fonts.mono, fontSize: 31, color: theme.colors.secondary}}>dsh-mobile.local</div>
            <div style={{height: 2, background: theme.colors.accent, margin: '18px 0', opacity: .5}} />
            <div style={{fontSize: 33, color: theme.colors.text}}>Workspace / Ready</div>
            <div style={{display: 'flex', gap: 9, marginTop: 20}}>{Array.from({length: 7}, (_, i) => <div key={i} style={{height: 8, flex: i === 0 ? 1.5 : 1, background: i === 0 ? theme.colors.success : theme.colors.accent, opacity: .6, borderRadius: 4}} />)}</div>
            <div style={{fontFamily: theme.fonts.mono, fontSize: 28, color: theme.colors.accent, marginTop: 23}}>大屏编辑 / 小屏执行</div>
          </div>
        </div>
        <div style={{position: 'absolute', left: 555, top: 72}}><PhoneModel width={290} height={598} /></div>
      </CameraRig>
      <svg width="950" height="850" viewBox="0 0 950 850" style={{position: 'absolute', inset: 0}}><defs><filter id="webui-glow"><feGaussianBlur stdDeviation="7" /></filter></defs><path d="M409 339C496 264 552 275 622 338" fill="none" stroke={theme.colors.accent} strokeWidth="13" opacity={.18} filter="url(#webui-glow)" /><path d="M409 339C496 264 552 275 622 338" fill="none" stroke={theme.colors.accent} strokeWidth="3" opacity={.85} strokeDasharray={`${bridge * 400} 400`} /><circle cx="622" cy="338" r="9" fill={theme.colors.success} opacity={bridge} /></svg>
      <div data-v4-text="webui-code" style={{position: 'absolute', left: 40, top: 530, fontFamily: theme.fonts.mono, fontSize: 38, color: theme.colors.accent, opacity: .7 + bridge * .3}}>CODE · {webuiEvidence.codeDigits} DIGITS</div>
    </div>
  </StudyStage>;
};
