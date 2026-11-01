/**
 * [dsh-android] profile 插件启动健康探针。
 *
 * 为什么必须在 Cordis 内观察：Loader 的 import 失败会被 Cordis 隔离，失败 entry
 * 可能没有 fiber，但进程仍能提供 HTTP，因此 Android 侧只检查端口无法判断工作区
 * host service 是否真的可用。探针等待 Loader 树收敛后检查服务注册表，再写一个短期
 * JSON marker，供 EngineSupervisor 在把 WebUI 暴露给用户前读取。
 *
 * marker 只用于诊断；探针不修改 profile、不停用包，有限且可回滚的恢复由 Android
 * 监督器执行。
 */
import fs from "node:fs";
import path from "node:path";

const name = "android-plugin-health";
const inject = ["loader"];
const REQUIRED_SERVICES = [
  "typert",
  "typertGateway",
  "directoryPicker",
  "directoryPickerController",
  "workspaceRegistry",
  "workspaceController",
  "webServer",
  "loader",
];
// `applyEntryPatches` 将 `name` 作为匹配守卫，而不是可赋值字段。
// 用户 patch 一旦改写关键行的 name，后续 Android overlay 无法仅按 id 恢复它。
// 将该行报告为 degraded，监督器才能隔离提供冲突 patch 的 bundle，避免继续提供
// 一个名义上存在、实际并非内置实现的 host service。
const CRITICAL_ROWS = {
  "typert": "@deepseek-ai/dsh-typert-registry",
  "typert-loader": "@deepseek-ai/dsh-typert-loader",
  "typert-gateway": "@deepseek-ai/dsh-api-gateway",
  // ⚠️ v1.4.8：这里**不能**写 "directory-picker"。Android overlay 现在是
  // 「禁用 base 的 directory-picker + insert 一条 android-directory-picker」，
  // 因为 patch 的 `name` 是匹配守卫而非可赋值字段（见 EngineConfig 里的详细说明）。
  // 原先写 "directory-picker": "...-browse" 会把那条已被禁用的 auto 行判成
  // "critical row must be browse"，probe 常年 degraded —— 而它自己校验的根本
  // 不是实际在跑的那一行。现在改为校验 insert 出来的新 id。
  "android-directory-picker": "@deepseek-ai/dsh-host-directory-picker-browse",
  // v1.4.10（用户反馈「工作区选不了外部文件夹，权限已给」）：picker 是**一对**
  // —— host 后端提供 directoryPicker 服务，client surface 提供界面控件
  // （它的 host 侧 apply() 是空的，唯一作用是让 browser bundle 被
  // dsh-client-modules 发现）。v1.4.8/v1.4.9 只挂了后端，服务在、界面不在，
  // 于是选择器根本弹不出来。这一行让探针能抓到这个不对称。
  "android-directory-picker-surface":
    "@deepseek-ai/dsh-client-ui-directory-picker-browse",
  "workspace": "@deepseek-ai/dsh-workspace",
  "workspace-controller": "@deepseek-ai/dsh-api-workspace-controller",
  "webserver": "@deepseek-ai/dsh-host-webserver",
  "storage-domain": "@deepseek-ai/dsh-storage-domain",
  "session-persistence-jsonl": "@deepseek-ai/dsh-session-persistence-jsonl",
};
// 被禁用或缺失的关键行没有 Loader entry 可供归因。补充合成行失败，
// 这样 PluginRecovery 仍能按 id 匹配禁用了对应内置行的第三方 patch。
const SERVICE_ROWS = {
  typert: [["typert", CRITICAL_ROWS.typert]],
  typertGateway: [["typert-gateway", CRITICAL_ROWS["typert-gateway"]], ["typert", CRITICAL_ROWS.typert]],
  directoryPicker: [
    ["android-directory-picker", CRITICAL_ROWS["android-directory-picker"]],
  ],
  directoryPickerController: [["workspace-controller", "@deepseek-ai/dsh-api-workspace-controller"]],
  workspaceRegistry: [
    ["workspace", CRITICAL_ROWS["workspace"]],
    ["storage-domain", CRITICAL_ROWS["storage-domain"]],
    ["session-persistence-jsonl", CRITICAL_ROWS["session-persistence-jsonl"]],
  ],
  workspaceController: [["workspace-controller", CRITICAL_ROWS["workspace-controller"]]],
  webServer: [["webserver", CRITICAL_ROWS.webserver]],
};
const MAX_WAIT_MS = 15000;
const POLL_MS = 250;

function markerPath() {
  const home = process.env.DSH_HOME || "";
  return home ? path.join(home, ".android-plugin-health.json") : "";
}

function safeGet(ctx, service) {
  try {
    return ctx.get(service);
  } catch {
    return void 0;
  }
}

/**
 * 取 loader 服务。⚠️ 不能写 `safeGet(ctx,"loader") || ctx.loader`：
 * Cordis 的 accessor 在 **context 已经 inactive**（引擎正在退出/重启）时会直接
 * throw「cannot get required service "loader" in inactive context」—— 而这句
 * 代码跑在 setInterval 的回调里，一旦抛出就是未捕获异常，整个引擎进程当场崩溃，
 * 监督器再拉起 → 再崩 → 无限重启循环（真机日志：engine.log 反复出现
 * `fatal uncaught exception ... plugin-health.mjs:219:50`）。这里统一 try/catch。
 */
function getLoader(ctx) {
  const viaGet = safeGet(ctx, "loader");
  if (viaGet) return viaGet;
  try {
    return ctx.loader;
  } catch {
    return void 0;
  }
}

function writeMarker(value) {
  const file = markerPath();
  if (!file) return;
  try {
    const temp = `${file}.tmp-${process.pid}`;
    // Root 模式下引擎由 uid 0 写 marker；0644 让 App uid 仍能读取诊断。
    // marker 不含凭证、会话内容或工作区文件名。
    fs.writeFileSync(temp, JSON.stringify(value) + "\n", { mode: 0o644 });
    // Android 进程的 umask 可能把 0644 收窄为 0600；Root 模式下 supervisor
    // 仍需读取这个无敏感信息 marker，因此显式恢复为其他用户可读。
    try { fs.chmodSync(temp, 0o644); } catch {}
    fs.renameSync(temp, file);
  } catch (error) {
    console.error(`[android-plugin-health] marker write failed: ${error?.message || error}`);
  }
}

/**
 * 提取 entry 的错误文本，并做两件事：
 *   ① **截断到 2KB** —— 完整 stack 可能带上整份配置，marker 会被 PluginRecovery
 *      拼进 engine.log，用户导出反馈时就外流了；
 *   ② **脱敏** —— 配置校验失败时的报错里可能带 apiKey/token 明文。
 * @param {unknown} error - Cordis 保存在 fiber 上的错误对象。
 * @returns {string} 安全、可写日志的错误摘要。
 */
function scrubError(error) {
  if (!error || typeof error !== "object") return "";
  const raw = String(error.stack || error.message || error) || "";
  const masked = raw
    // 常见密钥形态：sk-xxx / ghp_xxx / 长十六进制
    .replace(/(?<![A-Za-z0-9])(sk|ghp|gho|ghs)[-_][A-Za-z0-9_-]{8,}/g, "$1_***")
    .replace(/(?<![A-Za-z0-9])[A-Fa-f0-9]{32,}(?![A-Za-z0-9])/g, "***")
    // key: "..." / token=...
    .replace(/((?:api[_-]?key|token|secret|password)\s*[:=]\s*["']?)[^\s"',}]{6,}/gi, "$1***");
  return masked.length > 2048 ? masked.slice(0, 2048) + "…(truncated)" : masked;
}

/**
 * 目录选择器的**功能性**探测（issue #76）。
 *
 * 只做 `ctx.get("directoryPicker")` 是不够的：报告人的设备上探针 ok、网关却报
 * `directoryPickerController is unavailable`。两者能同时成立，因为网关是先
 * `resolveReceiverContext()` 拿子 context、再在那上面 get，而探针查的是根 context
 * —— 服务在根 context 里存在，不代表网关那条解析路径能取到它。
 *
 * 这里问的是后端自己的 `capability()`：能返回 `kind` 就说明 apply() 真的跑完了、
 * 后端对象是活的（不是只剩一个注册表占位）。返回空串表示正常；否则返回一段
 * 可直接写进 marker 的根因描述。
 *
 * @param {unknown} ctx - 宿主 context。
 * @returns {string} 空串（正常）或诊断文本。
 */
function probeDirectoryPicker(ctx) {
  const picker = safeGet(ctx, "directoryPicker");
  if (picker === void 0) return "";
  try {
    const capability = typeof picker.capability === "function" ? picker.capability() : void 0;
    if (capability === void 0) return "directoryPicker.capability() returned nothing (backend not applied?)";
    const kind = String(capability.kind || "");
    // task.md/§：Android 上永远是 browse（无 DISPLAY、无 zenity/kdialog）。
    // native 说明 overlay 没生效、base 的 auto 行还在跑 —— 这正是 #76 的形态。
    if (kind !== "browse") {
      return `directoryPicker capability is "${kind || "unknown"}" but Android needs "browse" (overlay not applied?)`;
    }
    if (typeof picker.list !== "function") return "directoryPicker.has no list()";
    return "";
  } catch (error) {
    return `directoryPicker probe failed: ${error?.message || error}`;
  }
}

function inspect(ctx, options = {}) {
  // timedOut=true 表示「等到超时仍有加载任务在跑」。此时未拿到 fiber 的 entry
  // 可能是**正在 import**，而不是加载失败 —— 必须区分，否则会把正常插件摘掉。
  const timedOut = options.timedOut === true;
  const loader = getLoader(ctx);
  const entries = loader && typeof loader.entries === "function" ? [...loader.entries()] : [];
  // 超时窗口内仍无 fiber 的 entry 归入 pending（不参与失败判定）
  const isPending = (entry) =>
    timedOut && entry.fiber === void 0 && !entry.disabled && !entry.options?.group;
  const failedEntries = entries
    .filter((entry) => {
      if (entry.disabled || entry.options?.group) return false;
      // import 失败没有 fiber；插件 init 抛错则保留 fiber，但 Cordis
      // 将其状态置为 3 并保存 _error。两种情况都表示 entry 不可用。
      //
      // ⚠️ 但「没有 fiber」在超时窗口里也可能只是**还没加载完**（Cordis 的
      // Entry.fiber 是在 await import() 成功之后才赋值）。这类必须排除，
      // 否则慢设备上正常插件会被误判、被 PluginRecovery 摘掉。
      if (isPending(entry)) return false;
      return entry.fiber === void 0 || entry.fiber.state === 3 || entry.fiber?._error !== void 0;
    })
    .map((entry) => ({
      id: String(entry.options?.id || entry.id || ""),
      name: String(entry.options?.name || ""),
      error: scrubError(entry.fiber?._error),
    }));
  // pending 项单独列出：供诊断，但不进入 failedEntries（不被隔离）
  const pendingEntries = entries.filter(isPending).map((entry) => ({
    id: String(entry.options?.id || entry.id || ""),
    name: String(entry.options?.name || ""),
  }));
  const criticalMismatches = entries
    .filter((entry) => Object.hasOwn(CRITICAL_ROWS, entry.options?.id || ""))
    .filter((entry) => entry.options?.name !== CRITICAL_ROWS[entry.options.id])
    .map((entry) => ({
      id: String(entry.options?.id || ""),
      name: String(entry.options?.name || ""),
      error: `critical row must be ${CRITICAL_ROWS[entry.options.id]}`,
    }));
  failedEntries.push(...criticalMismatches);
  const missingServices = REQUIRED_SERVICES.filter((service) => safeGet(ctx, service) === void 0);
  for (const service of missingServices) {
    const rows = SERVICE_ROWS[service] || [];
    for (const [id, name] of rows) {
      if (failedEntries.some((entry) => entry.id === id)) continue;
      // v1.4.3（用户反馈「数据迁移完成后 directoryPickerController is unavailable」）：
      // 只报「required service X is unavailable」时，用户和我们都无法知道**为什么**。
      // 这里把该行自己的 fiber 错误一并带出 —— 若是 workspace/storage 起不来导致
      // 服务缺失，根因（scandir EACCES、软链失效、注册表损坏…）就直接可见。
      const owner = entries.find((entry) => entry.id === id);
      const ownerError = owner?.fiber?._error
        ? String(owner.fiber._error?.stack || owner.fiber._error?.message || owner.fiber._error)
        : "";
      failedEntries.push({
        id,
        name,
        error: ownerError
          ? `required service ${service} is unavailable; row error: ${ownerError}`
          : `required service ${service} is unavailable`,
      });
    }
  }
  // v1.5.0（issue #76）：服务**在**注册表里也不代表能用。报告人的设备上探针 ok、
  // 网关却报 `directoryPickerController is unavailable` —— 两者能同时成立，因为
  // 探针只做 ctx.get()，网关是先 resolveReceiverContext 再在那上面 get。
  // 所以这里不管 service 是否 missing，都对 picker 做一次功能性探测。
  const pickerProblem = probeDirectoryPicker(ctx);
  if (pickerProblem) {
    const id = "android-directory-picker";
    const name = CRITICAL_ROWS[id];
    const existing = failedEntries.find((entry) => entry.id === id);
    if (existing) existing.error = `${existing.error}; ${pickerProblem}`;
    else failedEntries.push({ id, name, error: pickerProblem });
  }
  const profile = safeGet(ctx, "profileContext");
  const bundles = Array.isArray(profile?.startedBundles) ? profile.startedBundles.map(String) : [];
  const marker = {
    version: 1,
    bootId: process.env.DSH_ANDROID_BOOT_ID || "",
    pid: process.pid,
    at: Date.now(),
    // 超时判定：仍处于加载中的 entry 不算失败（见 inspect 顶部说明）。
    // timedOut 只用于诊断与日志，不参与 ok —— 否则慢设备永远报 degraded。
    timedOut,
    pendingEntries,
    ok: missingServices.length === 0 && failedEntries.length === 0,
    missingServices,
    failedEntries,
    bundles,
  };
  writeMarker(marker);
  if (!marker.ok) {
    console.error(
      `[android-plugin-health] profile is degraded: missing=${missingServices.join(",") || "none"} ` +
        `failed=${failedEntries.map((entry) => `${entry.id}:${entry.name}`).join(",") || "none"}`,
    );
  }
  return marker;
}

function apply(ctx) {
  const started = Date.now();
  let finished = false;
  let timer;
  const check = () => {
    if (finished) return;
    try {
      const loader = getLoader(ctx);
      const tasks = loader && typeof loader.getTasks === "function" ? loader.getTasks() : [];
      const elapsed = Date.now() - started;
      // 仍在加载中且没超时 → 继续等。
      if (tasks.length > 0 && elapsed < MAX_WAIT_MS) return;
      // ⚠️ v1.4.6 关键修复（用户反馈「装了插件重启显示加载失败」）：
      // 原来超时后**无条件** inspect，而 Cordis 的 Entry.fiber 是在 `await import()`
      // **成功之后**才赋值的 —— import 期间的 entry 看起来就是「fiber 未定义 = 失败」。
      // 于是：慢设备上某个正常插件 import 超过 15 秒 → 被判失败 → 监督器摘掉它 →
      // 用户看到「插件加载失败」，重装又好了（因为第二次加载更快/有缓存）。
      //
      // 现在超时后仍然 inspect（报告当前状态），但**标记 timedOut 并跳过失败判定**：
      // 未完成的 entry 归入 pending，由 PluginRecovery 忽略，绝不据此隔离插件。
      const timedOut = tasks.length > 0;
      finished = true;
      if (timer !== void 0) clearInterval(timer);
      inspect(ctx, { timedOut });
    } catch (error) {
      // 兜底：探针跑在定时器回调里，任何未捕获异常都会直接带崩引擎进程
      // （→ 监督器重启 → 再崩 → 无限重启循环）。这里吞掉并只写日志。
      finished = true;
      if (timer !== void 0) clearInterval(timer);
      console.error(`[android-plugin-health] check aborted: ${error?.message || error}`);
    }
  };
  timer = setInterval(check, POLL_MS);
  check();
  return () => {
    finished = true;
    if (timer !== void 0) clearInterval(timer);
  };
}

export { apply, inject, name };
