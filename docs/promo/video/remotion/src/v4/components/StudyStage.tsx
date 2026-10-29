import type {ReactNode} from 'react';
import {AbsoluteFill, useVideoConfig} from 'remotion';
import {theme} from '../../theme';

/**
 * 竖屏设计画布（1080×1920）→ 实际 composition 的等比适配。
 *
 * ## 为什么需要
 * v4 的全部场景都按竖屏 1080×1920 的绝对坐标写（left/top/width 都是硬值），
 * 横屏 composition 直接复用会把内容挤在左侧、右侧空一半，还会裁掉右边的元素。
 *
 * ## 做法
 * 把整棵舞台放进一个 1080×1920 的虚拟画布，再按实际尺寸等比缩放并居中：
 *   scale = min(actualW / 1080, actualH / 1920)
 * 横屏时整体缩小居中（留左右留白），竖屏时 scale = 1（零开销、零视觉变化）。
 *
 * ⚠️ 这是**适配**不是**重排**：横屏的内容密度比原生横屏布局低（有留白），
 * 但保证不裁切、不重叠、字号按比例仍可读。真正的双构图重排需要每个场景
 * 写两套坐标，工程量远大于本轮目标，已记入 FIXES_V4。
 */
const DesignCanvas = ({children}: {children: ReactNode}) => {
  const {width, height} = useVideoConfig();
  // 竖屏：scale=1（零变化）。横屏：整幅竖屏画布缩到能完整装进 1920×1080，
  // 左右自然留黑 —— 保证**不裁切**优先于**填满**。
  const scale = Math.min(width / 1080, height / 1920);
  return <AbsoluteFill style={{alignItems: 'center', justifyContent: 'center', overflow: 'hidden'}}>
    <div style={{
      width: 1080, height: 1920,
      transform: `scale(${scale})`, transformOrigin: 'center center',
      position: 'relative', flexShrink: 0,
    }}>
      {children}
    </div>
  </AbsoluteFill>;
};

export const StudyStage = ({eyebrow, title, subtitle, footer, children, accent = theme.colors.accent}: {
  eyebrow: string; title: ReactNode; subtitle?: ReactNode; footer: ReactNode; children: ReactNode; accent?: string;
}) => <DesignCanvas><AbsoluteFill style={{background: theme.colors.bg, color: theme.colors.text, fontFamily: theme.fonts.chinese, overflow: 'hidden'}}>
  <AbsoluteFill style={{zIndex: 0, background: `radial-gradient(ellipse at 58% 46%,${accent}12,transparent 52%),linear-gradient(120deg,#0C1116,#101820 55%,#0C1219)`}} />
  <svg width="1080" height="1920" viewBox="0 0 1080 1920" style={{position: 'absolute', inset: 0, opacity: .28}} aria-hidden="true">
    <defs><pattern id="v4-studio-grid" width="90" height="90" patternUnits="userSpaceOnUse"><path d="M90 0H0V90" fill="none" stroke="#7DD3FC" strokeWidth=".6" opacity=".12" /></pattern></defs>
    <rect width="1080" height="1920" fill="url(#v4-studio-grid)" />
    <path d="M65 230H138 M65 230V270 M1015 230H942 M1015 230V270 M65 1574H138 M65 1574V1534 M1015 1574H942 M1015 1574V1534" stroke={accent} strokeWidth="1.5" opacity=".22" />
  </svg>
  <div data-v4-safe-stage style={{position: 'absolute', left: 65, right: 65, top: 240, bottom: 362, display: 'flex', flexDirection: 'column', zIndex: 1}}>
    <div data-v4-text="heading" style={{flex: '0 0 244px', display: 'flex', flexDirection: 'column', gap: 16}}>
      <div style={{fontFamily: theme.fonts.mono, fontSize: 36, letterSpacing: '.12em', color: accent, lineHeight: 1.25}}>{eyebrow}</div>
      <div style={{fontSize: 84, fontWeight: 700, lineHeight: 1.13, letterSpacing: '-.035em'}}>{title}</div>
      {subtitle && <div style={{fontSize: 36, lineHeight: 1.3, color: theme.colors.secondary}}>{subtitle}</div>}
    </div>
    <div data-v4-visual-stage style={{position: 'relative', height: 850, flex: '0 0 850px'}}>{children}</div>
    <div data-v4-text="footer" style={{marginTop: 'auto', fontSize: 36, lineHeight: 1.45, color: theme.colors.secondary}}>{footer}</div>
  </div>
</AbsoluteFill></DesignCanvas>;
