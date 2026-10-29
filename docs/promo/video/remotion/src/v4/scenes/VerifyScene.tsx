import {useCurrentFrame} from 'remotion';
import {theme} from '../../theme';
import {accessibility} from '../data';
import {progress} from '../motion';
import {StudyStage} from '../components/StudyStage';
import {PhoneModel} from '../components/DeviceModels';
import {CameraRig} from '../components/CameraRig';
import {RoutedParticles} from '../components/RoutedParticles';

export const VerifyScene = () => {
  const frame = useCurrentFrame();
  const locked = progress(frame, 100, 48);
  const focus = progress(frame, 125, 45);
  return <StudyStage eyebrow="ACT. THEN VERIFY." title={<>点了，还要验中。</>} subtitle="AI 真的会用你的手机。" accent={theme.colors.success}
    footer={<><span style={{color: theme.colors.text}}>单步闭环 ≈ {accessibility.loopSeconds}s · 含决策、执行与验证</span><br />机制示意 · 真机单次实测验证 {accessibility.verifyMs}ms</>}>
    <svg width="950" height="850" viewBox="0 0 950 850" style={{position: 'absolute', inset: 0}} data-v4-graphics="closed-loop">
      <defs>
        <linearGradient id="verify-circuit" x1="0" x2="1"><stop stopColor={theme.colors.accent} /><stop offset=".5" stopColor={theme.colors.accentAlt} /><stop offset="1" stopColor={theme.colors.success} /></linearGradient>
        <radialGradient id="verify-volume"><stop stopColor={theme.colors.success} stopOpacity=".15" /><stop offset="1" stopColor={theme.colors.success} stopOpacity="0" /></radialGradient>
        <filter id="verify-bloom"><feGaussianBlur stdDeviation="14" /></filter>
      </defs>
      <ellipse cx="638" cy="333" rx="280" ry="300" fill="url(#verify-volume)" />
      <path d="M238 336C311 163 532 113 683 182C826 250 827 491 696 580C545 682 301 586 255 470" fill="none" stroke="url(#verify-circuit)" strokeWidth="2" opacity=".26" />
      <path d="M245 345C350 280 434 295 501 352" fill="none" stroke={theme.colors.accentAlt} strokeWidth="3" strokeOpacity=".5" />
      <path d="M733 540C692 662 462 729 296 659" fill="none" stroke={theme.colors.success} strokeWidth="3" strokeOpacity=".55" />
      <circle cx="649" cy="343" r="216" fill="none" stroke={theme.colors.success} strokeOpacity=".12" strokeWidth="20" filter="url(#verify-bloom)" />
      <circle cx="649" cy="343" r="216" fill="none" stroke={theme.colors.success} strokeOpacity=".9" strokeWidth="4" pathLength="1" strokeDasharray={`${.72 + locked * .28} 1`} transform="rotate(-90 649 343)" />
      <circle cx="649" cy="343" r="236" fill="none" stroke={theme.colors.success} strokeOpacity=".12" strokeWidth="1" strokeDasharray="2 13" />
      <circle cx="649" cy="343" r="195" fill="#101C22" fillOpacity=".9" stroke={theme.colors.success} strokeOpacity=".08" />
      <path d="M602 224L631 250L681 199" fill="none" stroke={theme.colors.success} strokeWidth="5" strokeLinecap="round" strokeLinejoin="round" />
      <path d="M428 343H450M848 343H870M649 104V126M649 560V582" stroke={theme.colors.success} strokeWidth="2" opacity=".65" />
    </svg>
    <CameraRig yaw={-15 + focus * 3} pitch={7 - focus * 3}>
      <div style={{position: 'absolute', left: 36, top: 121, transform: 'rotateZ(-10deg)', opacity: .65}}>
        <PhoneModel width={300} height={512}>
          <svg width="100%" height="100%" viewBox="0 0 300 512">
            <rect width="300" height="512" fill="#0C141D" />
            {[110,190,270,350].map((y, i) => <g key={y}><rect x="28" y={y} width="244" height="55" rx="12" fill={theme.colors.surfaceAlt} stroke={i === 1 ? theme.colors.success : theme.colors.accent} strokeOpacity={i === 1 ? .7 : .12} /><rect x="46" y={y + 20} width={110 - i * 12} height="8" rx="4" fill={theme.colors.secondary} opacity=".3" /><circle cx="237" cy={y + 27} r="12" fill={i === 1 ? theme.colors.success : '#1E3546'} /></g>)}
            <path d="M62 74H150V110M150 165V190M150 245V270M150 325V350" fill="none" stroke={theme.colors.accent} strokeWidth="2" strokeDasharray="4 5" opacity=".45" />
            <circle cx="237" cy="217" r="27" fill="none" stroke={theme.colors.success} strokeWidth="2" />
            <path d="M222 216L232 226L251 204" fill="none" stroke="#0C141D" strokeWidth="3" />
          </svg>
        </PhoneModel>
      </div>
    </CameraRig>
    <RoutedParticles frame={frame} width={950} height={850} duration={75} holdTrails routes={[
      {id: 'pick-act', delay: 0, color: theme.colors.accent, points: [{x: 170,y: 160},{x: 265,y: 42},{x: 408,y: 108},{x: 472,y: 185}]},
      {id: 'act-verify', delay: 50, color: theme.colors.accentAlt, points: [{x: 264,y: 351},{x: 346,y: 233},{x: 384,y: 322},{x: 442,y: 345}]},
      {id: 'verify-loop', delay: 108, color: theme.colors.success, points: [{x: 796,y: 500},{x: 837,y: 691},{x: 455,y: 742},{x: 298,y: 630}]},
    ]} />
    <div data-v4-text="verify-hero" style={{position: 'absolute', left: 471, top: 261, width: 356, display: 'flex', flexDirection: 'column', alignItems: 'center', gap: 12}}>
      <div style={{display: 'flex', alignItems: 'baseline', gap: 10}}><span style={{fontFamily: theme.fonts.numeric, fontSize: 164, lineHeight: 1, fontWeight: 300, letterSpacing: '-.06em', color: theme.colors.success, textShadow: `0 0 35px ${theme.colors.success}33`}}>69</span><span style={{fontSize: 56, color: theme.colors.success}}>ms</span></div>
      <div style={{fontFamily: theme.fonts.mono, fontSize: 36, lineHeight: 1.2, color: theme.colors.success, letterSpacing: '.12em'}}>VERIFIED</div>
    </div>
    <div data-v4-text="verify-copy" style={{position: 'absolute', left: 405, right: 0, top: 615, textAlign: 'center', fontSize: 42, fontWeight: 700, lineHeight: 1.35}}>页面变了，<br /><span style={{color: theme.colors.success}}>才算完成。</span></div>
    <div data-v4-text="loop-labels" style={{position: 'absolute', top: 770, left: 0, right: 0, display: 'flex', alignItems: 'center', gap: 18, fontSize: 36, color: theme.colors.secondary}}>
      <span style={{color: theme.colors.accent}}>定位</span><span>→</span><span style={{color: theme.colors.accentAlt}}>执行 {accessibility.actionSeconds}s</span><span>→</span><span style={{color: theme.colors.success}}>验证 {accessibility.verifyMs}ms</span>
    </div>
  </StudyStage>;
};
