import {useCurrentFrame} from 'remotion';
import {theme} from '../../theme';
import {StudyStage} from '../components/StudyStage';

export const IntroScene = () => {
  const frame = useCurrentFrame();
  const flash = Math.max(0, 1 - frame / 26);
  return <StudyStage eyebrow="DSH MOBILE · ANDROID NATIVE" title={<>完整 Agent，<br />手表也能跑。</>}
    subtitle="从手机，开始一条更短的路径。" footer={<><span style={{color: theme.colors.text}}>DeepSeek Harness</span> · 原生运行</>}>
    <div style={{position: 'absolute', inset: 0, display: 'flex', alignItems: 'center', justifyContent: 'center'}}>
      <svg width="900" height="780" viewBox="0 0 900 780" data-v4-graphics="intro-ring">
        <defs><radialGradient id="intro-glow"><stop stopColor={theme.colors.accent} stopOpacity=".24" /><stop offset="1" stopColor={theme.colors.accent} stopOpacity="0" /></radialGradient><filter id="intro-bloom"><feGaussianBlur stdDeviation="14" /></filter></defs>
        <circle cx="450" cy="360" r="292" fill="url(#intro-glow)" />
        <circle cx="450" cy="360" r="222" fill="none" stroke={theme.colors.accent} strokeWidth="2" strokeOpacity=".14" />
        <circle cx="450" cy="360" r="222" fill="none" stroke={theme.colors.accent} strokeWidth="10" strokeOpacity=".6" strokeDasharray="460 940" transform={`rotate(${frame * 3 - 75} 450 360)`} filter="url(#intro-bloom)" />
        <circle cx="450" cy="360" r="222" fill="none" stroke={theme.colors.accent} strokeWidth="4" strokeOpacity=".85" strokeDasharray="460 940" transform={`rotate(${frame * 3 - 75} 450 360)`} />
        <circle cx="450" cy="360" r="170" fill="#0B1117" stroke={theme.colors.success} strokeOpacity=".35" strokeWidth="2" />
        <path d="M397 355 Q398 304 445 310 Q489 312 503 345 Q526 335 533 312 Q548 348 523 371 Q501 405 457 402 Q419 400 399 382 L378 391 L386 365Z" fill="url(#intro-glow)" stroke={theme.colors.accent} strokeWidth="2" />
        <circle cx="430" cy="337" r="6" fill={theme.colors.accent} />
        <text x="450" y="488" textAnchor="middle" fill={theme.colors.success} fontFamily={theme.fonts.mono} fontSize="38" letterSpacing="6">ENGINE READY</text>
        <path d="M450 46V108M450 612V674M136 360H198M702 360H764" stroke={theme.colors.accent} strokeWidth="2" strokeOpacity=".35" />
        <rect x="126" y="350" width="648" height="20" rx="10" fill={theme.colors.accent} opacity={flash * .08} />
      </svg>
    </div>
  </StudyStage>;
};
