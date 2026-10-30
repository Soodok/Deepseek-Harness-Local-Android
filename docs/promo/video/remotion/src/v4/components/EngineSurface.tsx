import {theme} from '../../theme';

export const EngineSurface = ({round = false, ready = true, progress = 1}: {round?: boolean; ready?: boolean; progress?: number}) => {
  if (round) return <svg width="100%" height="100%" viewBox="0 0 500 500" data-engine-surface>
    <defs>
      <linearGradient id="engine-core"><stop stopColor={theme.colors.accent} /><stop offset="1" stopColor={theme.colors.success} /></linearGradient>
      <radialGradient id="engine-ambient"><stop stopColor={theme.colors.accent} stopOpacity=".15" /><stop offset="1" stopColor={theme.colors.bg} stopOpacity="0" /></radialGradient>
    </defs>
    <circle cx="250" cy="250" r="240" fill="#0B1117" />
    <circle cx="250" cy="250" r="220" fill="url(#engine-ambient)" />
    <circle cx="250" cy="250" r="213" fill="none" stroke={theme.colors.accent} strokeOpacity=".12" strokeWidth="2" />
    <circle cx="250" cy="250" r="213" fill="none" stroke="url(#engine-core)" strokeWidth="7" pathLength="1" strokeDasharray={`${progress} 1`} transform="rotate(-90 250 250)" strokeLinecap="round" />
    {Array.from({length: 48}, (_, i) => {
      const angle = i * Math.PI / 24;
      const r = i % 4 === 0 ? 193 : 197;
      return <line key={i} x1={250 + Math.cos(angle) * r} y1={250 + Math.sin(angle) * r} x2={250 + Math.cos(angle) * 202} y2={250 + Math.sin(angle) * 202} stroke={theme.colors.secondary} strokeOpacity={i % 4 === 0 ? .55 : .17} strokeWidth="2" />;
    })}
    <text x="250" y="139" textAnchor="middle" fill={theme.colors.secondary} fontFamily={theme.fonts.mono} fontSize="44" letterSpacing="2">DSH ENGINE</text>
    <path d="M188 231 Q189 183 238 186 Q281 188 291 220 Q314 210 318 187 Q337 217 312 242 Q293 274 246 275 Q211 274 188 254 L169 264 L176 240 Z" fill="url(#engine-core)" />
    <circle cx="219" cy="214" r="5" fill="#0B1117" />
    <path d="M237 186 Q237 166 223 162 M247 185 Q254 167 266 169" fill="none" stroke={theme.colors.accent} strokeWidth="5" strokeLinecap="round" />
    <text x="250" y="339" textAnchor="middle" fill={ready ? theme.colors.success : theme.colors.accent} fontFamily={theme.fonts.numeric} fontWeight="700" fontSize="54" letterSpacing="7">{ready ? 'READY' : 'BOOTING'}</text>
    <g stroke={theme.colors.accent} strokeWidth="2" strokeOpacity=".65" fill={theme.colors.accent}>
      <circle cx="202" cy="378" r="4" /><path d="M211 378H241" /><circle cx="250" cy="378" r="4" /><path d="M259 378H289" /><circle cx="298" cy="378" r="4" />
    </g>
  </svg>;
  return <svg width="100%" height="100%" viewBox="0 0 300 600" data-engine-surface>
    <rect width="300" height="600" rx="28" fill="#0B1117" />
    <rect x="23" y="72" width="254" height="3" rx="2" fill={theme.colors.accent} opacity=".32" />
    <circle cx="150" cy="225" r="61" fill="none" stroke={theme.colors.accent} strokeOpacity=".3" />
    <circle cx="150" cy="225" r="61" fill="none" stroke={theme.colors.success} strokeWidth="4" strokeDasharray="380" />
    <path d="M119 226L140 246L181 201" fill="none" stroke={theme.colors.success} strokeWidth="7" strokeLinecap="round" strokeLinejoin="round" />
    {[330,370,410].map((y, i) => <g key={y}><circle cx="39" cy={y} r="5" fill={theme.colors.success} /><rect x="58" y={y - 4} width={154 - i * 25} height="8" rx="4" fill={theme.colors.accent} opacity=".25" /></g>)}
    <rect x="26" y="490" width="248" height="54" rx="12" fill={theme.colors.surfaceAlt} />
    <path d="M46 512L55 520L46 528 M67 528H91" stroke={theme.colors.accent} strokeWidth="3" fill="none" />
  </svg>;
};
