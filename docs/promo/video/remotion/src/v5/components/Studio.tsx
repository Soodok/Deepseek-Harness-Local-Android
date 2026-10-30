import type {CSSProperties, ReactNode} from 'react';
import {AbsoluteFill, Img, staticFile, useCurrentFrame, useVideoConfig} from 'remotion';
import {theme} from '../../theme';
import {phase, mix, travel} from '../motion';

export const C = {
  bg: '#090F18', panel: '#121E2E', line: '#A7D9FF25', blue: '#7DD3FC',
  white: '#F2F5F9', muted: '#9EAEC3', purple: '#A78BFA', green: '#6EE7B7', amber: '#F2B36B',
} as const;
export const F = theme.fonts;
export const useFormat = () => {
  const {width, height} = useVideoConfig();
  return {landscape: width > height, width, height};
};
export const Box = ({children, style, ...rest}: {children?: ReactNode; style?: CSSProperties; [key: `data-${string}`]: unknown}) =>
  <div {...rest} style={{position: 'absolute', ...style}}>{children}</div>;

export const Backdrop = ({accent = C.blue, bright = false}: {accent?: string; bright?: boolean}) => {
  const frame = useCurrentFrame();
  const {width, height} = useFormat();
  return <AbsoluteFill style={{overflow: 'hidden', backgroundColor: C.bg}}>
    <AbsoluteFill style={{background: `radial-gradient(ellipse at ${61 + Math.sin(frame / 170) * 8}% 48%, ${accent}${bright ? '28' : '17'}, transparent 64%), linear-gradient(145deg,#111E30 0%,#090F18 52%,#10182A 100%)`}} />
    <svg width={width} height={height} style={{position: 'absolute', inset: 0, opacity: 0.34}}>
      <defs><pattern id="v5-grid" width="96" height="96" patternUnits="userSpaceOnUse"><path d="M96 0H0V96" fill="none" stroke="#9EDBFF" strokeWidth="0.65" opacity="0.14" /></pattern>
        <linearGradient id="v5-sweep"><stop stopColor={accent} stopOpacity="0"/><stop offset=".5" stopColor={accent} stopOpacity=".35"/><stop offset="1" stopColor={accent} stopOpacity="0"/></linearGradient>
      </defs>
      <rect width={width} height={height} fill="url(#v5-grid)" />
      <path d={`M ${width * 0.23} -200 L ${width * 0.95} ${height + 200}`} stroke="url(#v5-sweep)" strokeWidth="1"/>
      <path d={`M ${width * 0.56} -200 L ${width * 1.28} ${height + 200}`} stroke="url(#v5-sweep)" strokeWidth="1"/>
    </svg>
    <Box style={{left: -120, top: height * .45 + Math.sin(frame / 90) * 20, width: width + 240, height: 1, background: `linear-gradient(90deg,transparent,${accent}35,transparent)`, transform: 'rotate(-24deg)'}} />
  </AbsoluteFill>;
};

export const BrandMark = ({size = 64}: {size?: number}) => <Img src={staticFile('pet/brand.svg')} style={{width: size, height: size}} />;

export const Stage = ({number, label, title, subtitle, footer, accent = C.blue, children}: {
  number: string; label: string; title: ReactNode; subtitle?: ReactNode; footer: ReactNode;
  accent?: string; children: ReactNode;
}) => {
  const frame = useCurrentFrame();
  const {landscape: l, width} = useFormat();
  return <AbsoluteFill data-v5-scene={label} style={{fontFamily: F.sans, color: C.white}}>
    <Backdrop accent={accent} />
    <Box style={{left: l ? 96 : 76, top: l ? 54 : 88, right: l ? 96 : 76, height: 54, display: 'flex', alignItems: 'center', gap: 16}}>
      <BrandMark size={42}/><span style={{fontSize: 28, fontWeight: 700, letterSpacing: '-.02em'}}>DSH Mobile</span>
      <div style={{height: 1, flex: 1, background: C.line, marginLeft: 20, marginRight: 20}} />
      <span style={{fontFamily: F.mono, fontSize: l ? 23 : 25, color: C.muted}}>ANDROID / {number}</span>
    </Box>
    <Box style={{left: l ? 96 : 76, top: l ? 232 : 220, width: l ? 662 : 928, transform: `translateY(${travel(frame, 0, 24)}px)`}}>
      <div data-v5-text style={{fontSize: l ? 25 : 30, color: accent, fontFamily: F.mono, letterSpacing: '.15em', marginBottom: l ? 22 : 26}}>{label}</div>
      <div data-v5-text style={{fontFamily: F.chinese, fontSize: l ? 84 : 88, fontWeight: 700, lineHeight: 1.18, letterSpacing: '-.055em'}}>{title}</div>
      {subtitle && <div data-v5-text style={{marginTop: 26, fontSize: l ? 30 : 36, lineHeight: 1.5, color: C.muted}}>{subtitle}</div>}
    </Box>
    <Box data-v5-visual style={{left: l ? 816 : 64, top: l ? 158 : 610, width: l ? 1008 : 952, height: l ? 744 : 970}}>{children}</Box>
    <Box data-v5-text style={{left: l ? 96 : 76, top: l ? 955 : 1662, width: width - (l ? 192 : 152), fontSize: l ? 25 : 29, lineHeight: 1.45, color: C.muted}}>{footer}</Box>
    <Box style={{left: l ? 96 : 76, right: l ? 96 : 76, bottom: l ? 50 : 96, height: 3, background: '#FFFFFF12'}}>
      <div style={{height: 3, width: `${mix(.08, 1, phase(frame, 0, 280)) * 100}%`, background: accent, boxShadow: `0 0 16px ${accent}70`}} />
    </Box>
  </AbsoluteFill>;
};

export const Panel = ({children, style, accent = C.blue}: {children?: ReactNode; style?: CSSProperties; accent?: string}) =>
  <div style={{background: 'linear-gradient(145deg,#1A2B40DD,#101B2BEE)', border: `1px solid ${accent}30`, borderRadius: 28, boxShadow: '0 28px 70px #00000038', ...style}}>{children}</div>;

export const Chip = ({children, accent = C.blue, style}: {children: ReactNode; accent?: string; style?: CSSProperties}) =>
  <div data-v5-text style={{display: 'inline-flex', alignItems: 'center', gap: 12, padding: '13px 21px', background: `${accent}0D`, border: `1px solid ${accent}38`, borderRadius: 14, color: accent, fontSize: 27, lineHeight: 1.2, whiteSpace: 'nowrap', ...style}}>{children}</div>;

export const Metric = ({value, unit, label, accent = C.blue, size = 160}: {value: string; unit?: string; label?: string; accent?: string; size?: number}) =>
  <div data-v5-text>
    <div style={{fontFamily: F.numeric, fontWeight: 700, letterSpacing: '-.065em', lineHeight: .98, color: accent, display: 'flex', alignItems: 'baseline', gap: 12}}>
      <span style={{fontSize: size}}>{value}</span>{unit && <span style={{fontSize: size * .28, fontWeight: 400, letterSpacing: '-.025em'}}>{unit}</span>}
    </div>
    {label && <div style={{fontSize: 30, lineHeight: 1.3, color: C.muted, marginTop: 22}}>{label}</div>}
  </div>;

export const Ring = ({size = 500, progress = 1, accent = C.blue, children}: {size?: number; progress?: number; accent?: string; children?: ReactNode}) => {
  const frame = useCurrentFrame();
  return <div style={{position: 'relative', width: size, height: size}}>
    <svg width={size} height={size} viewBox="0 0 500 500" style={{position: 'absolute', inset: 0}}>
      <circle cx="250" cy="250" r="220" stroke={accent} strokeWidth="1" fill="none" opacity=".18" />
      <circle cx="250" cy="250" r="205" stroke={accent} strokeWidth="8" fill="none" opacity=".08" />
      <circle cx="250" cy="250" r="205" stroke={accent} strokeWidth="8" fill="none" strokeDasharray={`${1288 * progress} 1288`} transform="rotate(-90 250 250)" strokeLinecap="round" />
      <g transform={`rotate(${frame * .22} 250 250)`}><path d="M250 12 A238 238 0 0 1 472 164" fill="none" stroke={accent} strokeWidth="2" opacity=".55"/><circle cx="250" cy="12" r="4" fill={accent}/></g>
      {Array.from({length: 48}, (_, i) => <path key={i} d="M250 23v8" transform={`rotate(${i * 7.5} 250 250)`} stroke={accent} strokeWidth={i % 4 === 0 ? 2 : 1} opacity={i % 4 === 0 ? .55 : .16}/>) }
    </svg>
    <div style={{position: 'absolute', inset: 0, display: 'flex', flexDirection: 'column', alignItems: 'center', justifyContent: 'center'}}>{children}</div>
  </div>;
};

export const ScreenPhoto = ({file, width, height, cropTop = 100, pan = 0, style}: {file: string; width: number; height: number; cropTop?: number; pan?: number; style?: CSSProperties}) =>
  <div style={{position: 'relative', width, height, overflow: 'hidden', borderRadius: 24, background: '#101418', ...style}}>
    <Img src={staticFile(`assets/${file}`)} style={{position: 'absolute', left: 0, top: -(cropTop + pan) * width / 1080, width, maxWidth: 'none'}} />
  </div>;

export const Phone = ({file, width = 380, height = 750, children, style, cropTop = 100}: {file?: string; width?: number; height?: number; children?: ReactNode; style?: CSSProperties; cropTop?: number}) =>
  <div style={{position: 'relative', width, height, borderRadius: 52, padding: 11, background: 'linear-gradient(135deg,#6F89A6,#1A2637 15%,#0A111B 60%,#56718D)', boxShadow: '0 44px 90px #0009, 0 0 0 1px #CDE9FF50', ...style}}>
    <div style={{position: 'relative', width: width - 22, height: height - 22, overflow: 'hidden', borderRadius: 42, background: C.bg, border: '2px solid #03070D'}}>
      {file && <ScreenPhoto file={file} cropTop={cropTop} width={width - 26} height={height - 26} style={{borderRadius: 39}}/>}
      {children}
    </div>
    <div style={{position: 'absolute', left: width - 2, top: 150, width: 4, height: 72, borderRadius: 3, background: '#7D91AC'}} />
  </div>;

export const PetClip = ({clip = 'idle', start = 0, width = 420, loop = true, style}: {clip?: 'idle' | 'coding' | 'celebrate'; start?: number; width?: number; loop?: boolean; style?: CSSProperties}) => {
  const frame = useCurrentFrame();
  const raw = Math.max(0, Math.floor((frame - start) * 24 / 60));
  const index = loop ? raw % 96 : Math.min(95, raw);
  return <Img src={staticFile(`pet/${clip}/${String(index).padStart(4, '0')}.png`)}
    style={{width, height: width * 332 / 313, objectFit: 'contain', ...style}} />;
};
