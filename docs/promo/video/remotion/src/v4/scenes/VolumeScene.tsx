import {useCurrentFrame} from 'remotion';
import {theme} from '../../theme';
import {facts, copy} from '../../data';
import {StudyStage} from '../components/StudyStage';

const Group = ({title, rows, note, active}: {title: string; rows: {name: string; value: number; color?: string; approximate?: boolean}[]; note: string; active: boolean}) => {
  const max = Math.max(...rows.map((row) => row.value));
  return <div data-volume-group style={{display: active ? 'flex' : 'none', flexDirection: 'column', gap: 13}}>
    <div data-v4-text="volume-title" style={{fontSize: 42, fontWeight: 700}}>{title}</div>
    <div data-volume-chart style={{display: 'flex', flexDirection: 'column', gap: 9, borderRadius: 22, background: theme.colors.surfaceAlt, border: `1px solid ${theme.colors.divider}`, padding: '18px 22px'}}>
      {rows.map((row) => <div key={row.name} style={{display: 'flex', flexDirection: 'column', gap: 6}}>
        <div style={{display: 'flex', justifyContent: 'space-between', alignItems: 'baseline', gap: 15}}>
          <span style={{fontSize: 38, color: row.color ?? theme.colors.secondary}}>{row.name}</span>
          <span style={{fontFamily: theme.fonts.numeric, fontSize: row.color ? 48 : 40, color: row.color ?? theme.colors.secondary}}>{row.approximate ? '≈' : ''}{row.value}</span>
        </div>
        <div data-volume-bar style={{height: row.color ? 20 : 16, borderRadius: 8, background: '#0C151F', overflow: 'hidden'}}><div style={{height: '100%', width: `${row.value / max * 100}%`, borderRadius: 8, background: row.color ?? theme.colors.muted}} /></div>
      </div>)}
    </div>
    <div style={{fontSize: 36, lineHeight: 1.3, color: theme.colors.secondary}}>{note}</div>
  </div>;
};

export const VolumeScene = () => {
  const frame = useCurrentFrame();
  const index = Math.min(2, Math.floor(frame / 180));
  const groups = [
    {title: '01 / 安装包体积', rows: facts.install.map((r) => ({name: r.label, value: r.value, color: r.ours ? theme.colors.success : undefined})), note: '我们不是最小的。'},
    {title: '02 / 首次可用总下载', rows: facts.firstUse.map((r) => ({name: r.label, value: r.value, approximate: r.approximate, color: r.ours ? theme.colors.success : undefined})), note: '两种包首次总下载均约 150 MB。'},
    {title: '03 / 解压后磁盘占用', rows: facts.extracted.map((r) => ({name: r.label, value: r.value, color: r.ours ? theme.colors.success : undefined})), note: '本作排第 4（从大到小）。DSHBox 未知。'},
  ];
  return <StudyStage eyebrow="THE COMPLETE PICTURE" title={<>体积，<br />完整地看。</>} footer={<>{groups[index].note}<br />{index === 0 ? copy.scale.installNote : index === 1 ? copy.scale.downloadNote : copy.scale.rankNote}</>}><div style={{position: 'absolute', inset: 0, display: 'flex', flexDirection: 'column', justifyContent: 'center'}}>{groups.map((group, i) => <Group key={group.title} title={group.title} rows={group.rows} note={group.note} active={i === index} />)}</div></StudyStage>;
};
