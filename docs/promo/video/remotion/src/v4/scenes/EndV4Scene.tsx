import {useCurrentFrame} from 'remotion';
import {theme} from '../../theme';
import {copy} from '../../data';
import {StudyStage} from '../components/StudyStage';

export const EndV4Scene = () => {
  const frame = useCurrentFrame();
  const opacity = Math.min(1, .7 + frame / 36);
  return <StudyStage eyebrow="DSH MOBILE" title={<>DeepSeek Harness.<br /><span style={{color: theme.colors.accent}}>Now in your pocket.</span></>} footer={<><span style={{color: theme.colors.text}}>完整 Agent，在 Android 原生运行。</span><br />社区移植 · 模型请求使用你配置的 API</>}>
    <div style={{position: 'absolute', inset: 0, display: 'flex', flexDirection: 'column', alignItems: 'center', justifyContent: 'center', gap: 35, textAlign: 'center', opacity}}>
      <div data-v4-text="end-no" style={{display: 'flex', gap: 22}}>{copy.end.no.map((label) => <div key={label} style={{padding: '18px 25px', borderRadius: 18, border: `1px solid ${theme.colors.accent}55`, background: theme.colors.surfaceAlt, color: theme.colors.accent, fontFamily: theme.fonts.sans, fontSize: 42, fontWeight: 700}}>{label}</div>)}</div>
      <div style={{fontSize: 42, lineHeight: 1.35}}>完整 Agent · Android 8.0 → 16</div>
      <div style={{fontFamily: theme.fonts.mono, fontSize: 38, color: theme.colors.secondary}}>github.com/Soodok/Deepseek-Harness-Local-Android</div>
    </div>
  </StudyStage>;
};
