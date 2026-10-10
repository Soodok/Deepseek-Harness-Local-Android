// 嵌入服务端到端测试（v1.2.121）—— 验证本地语义匹配的实际延迟
// 用法: node test_embed.mjs
const URL = "http://127.0.0.1:3085/v1/embeddings";

// 模拟 FastPicker 的真实使用：目标描述 + 屏幕上的若干文本节点
const target = "打开 WLAN";
const screenTexts = ["搜索设置", "网络和互联网", "移动网络、WLAN、热点", "已连接的设备", "应用", "通知"];

async function embed(inputs) {
  const r = await fetch(URL, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ input: inputs }),
  });
  const d = await r.json();
  return d.data.map((x) => x.embedding);
}

function cosine(a, b) {
  let dot = 0, na = 0, nb = 0;
  for (let i = 0; i < a.length; i++) { dot += a[i] * b[i]; na += a[i] * a[i]; nb += b[i] * b[i]; }
  return dot / (Math.sqrt(na) * Math.sqrt(nb));
}

console.log("=== 本地嵌入服务实测 ===");
const t0 = Date.now();
const vecs = await embed([target, ...screenTexts]);
const first = Date.now() - t0;
console.log(`首次（含模型预热）: ${first}ms，向量维度 ${vecs[0].length}`);

// 后续请求的延迟（FastPicker 每次实际只付这个）
const times = [];
let bestIdx = -1, bestScore = -1;
for (let round = 0; round < 3; round++) {
  const s = Date.now();
  const v = await embed([target, ...screenTexts]);
  times.push(Date.now() - s);
  let bi = -1, bs = -1;
  for (let i = 1; i < v.length; i++) {
    const sc = cosine(v[0], v[i]);
    if (sc > bs) { bs = sc; bi = i - 1; }
  }
  bestIdx = bi; bestScore = bs;
  console.log(`  第 ${round + 1} 轮: ${times[times.length - 1]}ms`);
}

console.log(`\n后续平均延迟: ${(times.reduce((a, b) => a + b, 0) / times.length).toFixed(1)}ms`);
console.log(`目标「${target}」→ 命中「${screenTexts[bestIdx]}」，相似度 ${bestScore.toFixed(3)}`);

// 对照组：应低分
const neg = await embed(["打开 WLAN", "铃声和振动"]);
console.log(`对照组（铃声和振动）: ${cosine(neg[0], neg[1]).toFixed(3)}`);
