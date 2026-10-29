import {useCurrentFrame} from 'remotion';
import {theme} from '../../theme';
import {communityEvidence} from '../data';
import {progress} from '../motion';
import {StudyStage} from '../components/StudyStage';
import {RoutedParticles} from '../components/RoutedParticles';

export const CommunityScene = () => {
  const frame = useCurrentFrame();
  const main = progress(frame, 126, 48);
  return <StudyStage eyebrow="NOT ONE PERSON'S PROJECT." title={<>社区，汇入主干。</>} subtitle="每一条贡献，都让它更完整。" footer="贡献结构示意 · 不代表提交时间或数量统计。">
    <div style={{position: 'absolute', inset: 0}}>
      <svg width="950" height="850" viewBox="0 0 950 850" style={{position: 'absolute', inset: 0}}>
        <defs><linearGradient id="main-branch"><stop stopColor={theme.colors.accentAlt} /><stop offset=".5" stopColor={theme.colors.accent} /><stop offset="1" stopColor={theme.colors.success} /></linearGradient></defs>
        <path d="M475 700V390" fill="none" stroke="url(#main-branch)" strokeWidth="8" strokeLinecap="round" />
        <path d="M475 430C400 340 286 310 170 230M475 430C560 329 684 290 816 220M475 490C585 454 698 463 830 430" fill="none" stroke="url(#main-branch)" strokeWidth="3" strokeOpacity=".6" />
        <circle cx="475" cy="390" r="35" fill="#0C171F" stroke={theme.colors.success} strokeWidth="3" opacity={main} /><path d="M459 390L470 401L493 374" fill="none" stroke={theme.colors.success} strokeWidth="5" strokeLinecap="round" strokeLinejoin="round" opacity={main} />
        <text x="475" y="769" textAnchor="middle" fill={theme.colors.success} fontFamily={theme.fonts.mono} fontSize="32" letterSpacing="5">RELEASE / MAIN</text>
      </svg>
      <RoutedParticles frame={frame} width={950} height={850} duration={75} routes={communityEvidence.map((entry, i) => ({id: entry.name, delay: i * 42, color: i === 0 ? theme.colors.accent : i === 1 ? theme.colors.accentAlt : theme.colors.success, points: [{x: i === 0 ? 170 : i === 1 ? 816 : 830, y: i === 2 ? 430 : 220},{x: i === 0 ? 245 : i === 1 ? 690 : 700, y: i === 2 ? 380 : 180},{x: 452, y: 375},{x: 475, y: 390}]}))} />
      {communityEvidence.map((entry, i) => <div key={entry.name} data-v4-text="community-entry" style={{position: 'absolute', left: i === 0 ? 24 : i === 1 ? 596 : 618, top: i === 2 ? 463 : i === 0 ? 160 : 155, width: 310, padding: '18px 20px', borderRadius: 18, border: `1px solid ${theme.colors.accent}55`, background: '#121E28DD', opacity: progress(frame, i * 42, 30)}}><div style={{fontFamily: theme.fonts.mono, fontSize: 34, color: i === 0 ? theme.colors.accent : i === 1 ? theme.colors.accentAlt : theme.colors.success}}>{entry.name}</div><div style={{fontSize: 31, color: theme.colors.secondary, marginTop: 8, lineHeight: 1.3}}>{entry.detail}</div></div>)}
    </div>
  </StudyStage>;
};
