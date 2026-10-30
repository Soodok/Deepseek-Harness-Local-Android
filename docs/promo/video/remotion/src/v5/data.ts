// Fact values are transcribed from MATERIALS.md, README.md and ACCESSIBILITY.md.
// Edit durations here; all scene boundaries use a single, non-overlapping timeline.
export const v5Film = {
  version: '5.0.0',
  fps: 60,
  durationInFrames: 3120,
  shots: [
    {id: 'intro', from: 0, duration: 180, label: '完整 Agent，装进口袋'},
    {id: 'performance', from: 180, duration: 420, label: '小到手表，完整开跑'},
    {id: 'extensions', from: 600, duration: 390, label: '21 项环境扩展'},
    {id: 'plugins', from: 990, duration: 270, label: '社区插件，一键装卸'},
    {id: 'accessibility', from: 1260, duration: 360, label: 'AI 直接操作手机'},
    {id: 'ocr', from: 1620, duration: 210, label: '看得更快，验得更准'},
    {id: 'webui', from: 1830, duration: 330, label: '大屏编辑，小屏执行'},
    {id: 'size', from: 2160, duration: 330, label: '轻装上阵，按需下载'},
    {id: 'pet', from: 2490, duration: 360, label: '你忙，它也在'},
    {id: 'end', from: 2850, duration: 270, label: 'DSH Mobile'},
  ],
} as const;

export type ShotId = typeof v5Film.shots[number]['id'];

export const facts = {
  performance: {
    flagship: {label: '一加 15T', seconds: 3.4},
    mainstream: {label: '主流中端', seconds: '5–6'},
    watch: {label: '紫光展锐手表', seconds: 13, ramGb: 3},
    source: 'MATERIALS.md §B / README.md 实测数字',
  },
  extensions: 21,
  plugins: 3540,
  categories: 23,
  accessibility: {
    loopSeconds: 1.45,
    ocrBefore: '1370–1588',
    ocrAfter: '326–393',
    source: 'ACCESSIBILITY.md §2 / §7，单步闭环为真机单次实测',
  },
  size: {
    liteMb: 24, runtimeMb: 128, firstUseMb: 152, fullMb: 149,
    competitors: [
      {name: 'DSHBox', version: '1.5.0', mb: 112},
      {name: 'guzhou079', version: '1.42.13', mb: 118},
      {name: 'woaiys3', version: '1.20.0', mb: 155},
      {name: 'DSHA', version: '0.2.1-rc.2', mb: 279},
      {name: 'dphmoblie', version: '0.3.5', mb: 392},
    ],
    source: 'MATERIALS.md §A 安装包口径，v1.4.36 时期快照',
  },
  pet: {
    contributor: '@XiaoPb', kotlinModules: 24,
    source: 'PLAN_V4.md §4 统计：24 个 Kotlin 文件；PET_ARCHITECTURE.md 顶部为较早快照',
  },
} as const;

export const tools = [
  {name: 'Python', mark: 'Py', color: '#7DD3FC'},
  {name: 'Go', mark: 'Go', color: '#6EE7B7'},
  {name: 'Rust', mark: 'Rs', color: '#F2B36B'},
  {name: 'JDK 17', mark: 'J', color: '#A78BFA'},
  {name: 'FFmpeg', mark: 'Ff', color: '#6EE7B7'},
  {name: 'OpenSSH', mark: '>_', color: '#7DD3FC'},
] as const;

export const pickups = [
  {id: 'plugin-install', shot: 'plugins', hint: '插件市场安装一个插件', seconds: 8},
  {id: 'phone-control', shot: 'accessibility', hint: 'AI 打开设置并完成一次操作', seconds: 8},
  {id: 'webui-session', shot: 'webui', hint: '电脑 Web UI 编辑，手机同步执行', seconds: 8},
] as const;
