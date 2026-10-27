# 压缩包格式支持（解压能力清单）

一句话：**本应用所有"用户可选择文件"的导入入口，都只接受标准 ZIP 压缩包（`.zip`）。**
rar / 7z / tar / tar.gz / xz / bz2 不做支持，也不做"猜格式"——文件头不是 PK 签名就直接早退。

## 1. 用户能选文件的入口

| 入口 | 只接受 | 实现 | 限制 |
|---|---|---|---|
| 桌面宠物 → 从文件导入（`.zip`） | `.zip` | `PetPackInstaller`，`ZipInputStream` 两遍流式 | 见下表 |
| 设置 → 导入数据 | `.zip` | `DataExport`，`ZipFile` 随机访问 | 条目/单文件/总量上限，路径白名单 |
| 设置 → 导出数据（写） | 产出 `.zip` | `DataExport`，`ZipOutputStream` | — |

文件选择器的 MIME 过滤：`application/zip` + `application/x-zip-compressed`，桌宠导入额外放行
`application/octet-stream`（部分文件管理器把 zip 报成这个类型）。APK 的 MIME
（`application/vnd.android.package-archive`）不在列表里，选不到；即使绕过选择器，导入器也会按
显示名/MIME 明确拒绝。

## 2. ZIP 能力边界（两个入口一致）

| 项 | 支持情况 |
|---|---|
| 容器格式 | ZIP（`PK\x03\x04`）、空包目录（`PK\x05\x06`）、分卷签名（`PK\x07\x08` 头） |
| 压缩方法 | `STORED`（不压缩）、`DEFLATE`（标准 zip 默认） |
| ZIP64 | 支持（JDK 7+ 的 `ZipInputStream`/`ZipFile` 内置） |
| 加密条目 | **不支持**（ZipCrypto / AES-256）→ 明确提示"不支持加密压缩包" |
| 多卷/分卷（`.z01` `.z02`） | 不支持 |
| 其他压缩方法（bzip2 / zstd / LZMA 封进 zip） | 不支持（JDK 无法解码） |
| 路径穿越、绝对路径、盘符 | 拒绝（zip-slip 防护） |
| 条目数 / 单条目 / 总解压量 | 硬上限（桌宠包 2000 / 50MB / 600MB） |

## 3. "格式上是 zip、但产品上不收"的后缀

`.apk` `.jar` `.war` `.epub` `.docx` `.xlsx` `.pptx` `.odt` `.vsix` 等本质都是 ZIP 容器，
解析层面能读，但**不是合法资源包/备份包**（内部目录结构不同），因此：

- `.apk` 明确拒绝并提示"这是应用安装包，请选择 .zip 资源包"；
- 其余通过 MIME 过滤进不来（即便强行传入，也会因缺少 `pet-config.json` / `manifest.json` 被拒）。

## 4. 内部链路（用户不选文件，但确实在解压）

| 场景 | 格式 | 实现 |
|---|---|---|
| 运行时安装 `runtime.zip` | `.zip` | `RuntimeInstaller`，`ZipFile` 预扫 + `ZipInputStream` 解压 |
| 环境扩展（Termux 包） | `.deb` → `ar` 归档 → `data.tar.{xz,gz,tar}` | `ExtensionManager`；`XZInputStream` / `GZIPInputStream` / 自研 tar（GNU longname、PAX、symlink、硬链接） |
| 插件安装（npm 生态） | `.tgz` | 由 Node/npm 自行处理，不走 Kotlin 解压 |

> 也就是说：**算法层面**应用内有 gzip、xz、tar、ar 的解码能力，但它们只服务于上面三条内部链路，
> 不对外提供"导入任意压缩包"的能力。

## 5. 明确不支持

`rar` `7z` `zipx` `tar` `tar.gz` / `tgz` `gz` `xz` `bz2` `zst` `lz4` `cab` `iso` `dmg`
→ 用户需要先自行解压，把内容重新打成**标准 zip（不建议设密码）**再导入。

## 6. 为什么不做 rar/7z

- 需要引入第三方解压库（junrar / commons-compress / 7-Zip JNI），APK 体积、许可合规、攻击面三样都要付代价；
- 实际分发形态里，资源包与备份包都是我们自己产出的 zip（或用户用 zip 工具打的包），已覆盖绝大多数场景；
- 真要扩展，`PetPackInstaller` 的校验层（文件头 → 条目白名单 → 限额 → 原子替换）可以原样复用，
  只需把最底层的 `ZipInputStream` 换成对应的归档读取实现。
