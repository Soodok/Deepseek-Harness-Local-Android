export type V4Shot = {id: string; from: number; duration: number};

export const v4Film = {
  fps: 60,
  durationInFrames: 3120,
  // v4.1：桌宠镜**下线** —— 代码画的角色与应用里真实的「蓝色大肥鱼」资源包差距太大，
  // 用假角色只会减分。素材包 127MB、下载通道不稳，暂无法取得真实帧。
  // 时间重新分配给四个主角与社区段；桌宠只在社区段用一句话带过（不画角色）。
  shots: [
    {id: 'intro', from: 0, duration: 120},
    {id: 'devices', from: 120, duration: 450},
    {id: 'tools', from: 570, duration: 330},
    {id: 'install', from: 900, duration: 210},
    {id: 'verify', from: 1110, duration: 330},
    {id: 'perception', from: 1440, duration: 270},
    {id: 'webui', from: 1710, duration: 240},
    {id: 'native', from: 1950, duration: 180},
    {id: 'volume', from: 2130, duration: 420},
    {id: 'community', from: 2550, duration: 330},
    {id: 'end', from: 2880, duration: 240},
  ] as const satisfies readonly V4Shot[],
} as const;

export const keyframes = {devices: 540, tools: 870, verify: 1290} as const;

// MATERIALS.md §B: different devices, not a synchronized recording or competitor test.
export const performance = {
  flagship: {seconds: 3.4, label: '一加 15T'},
  midrange: {minSeconds: 5, maxSeconds: 6, label: '主流中端'},
  watch: {seconds: 13, memoryGb: 3, label: '紫光展锐手表'},
  emulator: {minSeconds: 2.2, maxSeconds: 2.7, memoryGb: 2.5, label: 'x86_64 模拟器'},
} as const;

// docs/ACCESSIBILITY.md §2 / §7. The loop includes decision time.
export const accessibility = {
  loopSeconds: 1.45,
  actionSeconds: 1.13,
  verifyMs: 69,
  ocrMs: '326–393',
  previousOcrMs: '1370–1588',
} as const;

// Exact identities from app/src/main/assets/extensions/catalog.json (21 items).
export const tools = [
  {id: 'python', name: 'Python', mark: 'Py'},
  {id: 'openjdk-17', name: 'OpenJDK 17', mark: 'J'},
  {id: 'golang', name: 'Go', mark: 'Go'},
  {id: 'rust', name: 'Rust', mark: 'Rs'},
  {id: 'ruby', name: 'Ruby', mark: 'Rb'},
  {id: 'php', name: 'PHP', mark: 'P'},
  {id: 'lua', name: 'Lua', mark: 'L'},
  {id: 'perl', name: 'Perl', mark: 'Pl'},
  {id: 'clang', name: 'Clang / LLVM', mark: 'C'},
  {id: 'git', name: 'Git', mark: 'G'},
  {id: 'archivers', name: 'Archivers', mark: 'Z'},
  {id: 'android-buildtools', name: 'Android build tools', mark: 'A'},
  {id: 'cmake', name: 'CMake', mark: 'CM'},
  {id: 'sqlite', name: 'SQLite', mark: 'SQL'},
  {id: 'ffmpeg', name: 'FFmpeg', mark: 'F'},
  {id: 'imagemagick', name: 'ImageMagick', mark: 'IM'},
  {id: 'openssh', name: 'OpenSSH', mark: 'SSH'},
  {id: 'android-tools', name: 'ADB / Fastboot', mark: 'ADB'},
  {id: 'vim', name: 'Vim', mark: 'V'},
  {id: 'llama-cpp', name: 'llama.cpp', mark: 'AI'},
  {id: 'asr-service', name: 'Offline ASR', mark: 'ASR'},
] as const;

export const extensionEvidence = {
  count: 21,
  stages: ['依赖闭包', 'SHA-256', 'shebang 修复', 'rename 发布'],
  notes: ['ELF 架构断言属于运行时入口 node', '不完整不报成功'],
} as const;

export const webuiEvidence = {
  port: 3090,
  codeDigits: 6,
  title: '大屏编辑，小屏执行。',
} as const;

export const petEvidence = {
  kotlinModules: 24,
  contributor: '@XiaoPb',
  bridge: '@xiaopb/dsh-pet-bridge ≥ 0.3.1',
  prerequisites: '资源包 · 桥插件 · 悬浮窗权限',
} as const;

export const communityEvidence = [
  {name: '@XiaoPb', detail: '桌宠资源包 · 运行时复用'},
  {name: '@Masspedro', detail: 'APK 回调 · pt-BR'},
  {name: '@xiaoming132887', detail: '灵动岛 · 真 PTY'},
] as const;
