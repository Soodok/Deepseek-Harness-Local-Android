华为 Mate40 的问题定位并修复了，请更新到 **v1.2.49** 再试。

### 根因

截图里的 `AbortSignal.any is not a function` 来自 dsh 前端的 pdf.js（文件预览组件），它要求 **Chrome 116+**，而 Mate40 的 WebView 版本低于此。我全量扫描了引擎运行时，发现兼容补丁把门槛覆盖到 Chrome 93 就不够了 —— 引擎实际需要 **Chrome 126**：

| 缺失 API | 需要 | 用途 |
|---|---|---|
| `URL.parse()` | Chrome 126 | pdf.js |
| `Promise.withResolvers()` | Chrome 119 | 10 个客户端插件 |
| `AbortSignal.any()` | Chrome 116 | 9 个插件（含你报的这个）|
| `toSorted` / `structuredClone` / `findLast` | 97–110 | 多处 |

v1.2.49 的补丁已把这些**全部补齐**（每个都有 `if (!api)` 守卫，新 WebView 不受影响）。

### 另外：现在能自己看到 WebView 版本了

v1.2.49 起 App 会把 WebView 环境写进日志，形如：

```
[webview] console/ERROR .../assets/client.pdf.js:1 Uncaught TypeError: AbortSignal.any is not a function
[dsh-android] webview ok | chrome=83 | polyfill=complete | ua=...
```

**如果更新后还有问题**，请在 设置 里导出日志（或看 `engine.log`）把带 `[webview]` 的行发我 —— 里面会直接写明 WebView 版本和缺哪个 API，就不用再靠猜了。

### 一个仍然无解的情况（先说明）

如果日志显示 `chrome=84` 以下，可能会撞见 **`??=` 语法错误**（需要 Chrome 85）—— 这类**语法特性 polyfill 救不了**（错误发生在代码解析阶段）。那种情况只能靠系统更新 WebView。但你的报错信息说明代码已经执行到 pdf.js 了，所以**不属于这一类**，补 API 就能解决。
