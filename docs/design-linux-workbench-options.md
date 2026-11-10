# Linux 工作台 · 容器选项与 AI 能力设计

> 本轮（2026-11-09）用户要求：「给 linux 工作台加上安装容器一些 UI 选项，并且暴露 API 给 AI」

## 一、为什么不是「随便加几个发行版」

加发行版前先问一句：**它能在这个容器方案里真的跑起来吗？** 判据是三条硬条件，
缺任何一条都不该出现在选项里（否则用户点了只会拿到一个起不来的容器）：

1. **rootfs 能在这个 Android 上解包并 chroot/seccomp 进入** —— 需要目标架构的
   minirootfs，且动态链接器路径能写进 `interpPath`
2. **包管理器可用** —— `apk`（musl）或 `apt`（glibc），且镜像在 CN 可达
3. **node 能装上** —— 引擎自己要 node 才能跑（`NodeStrategy` 决定装法）

所以本轮**不是**把 Debian/Fedora/Arch 全塞进去，而是把「同一发行版的不同**形态**」
和「真正有意义的第三种」做出来。

## 二、本轮新增的选项

### 1. Alpine 的两个新形态（同一 rootfs 复用，零额外下载）

| 选项 | 与 base 的区别 | 为什么值得有 |
|---|---|---|
| **Alpine + 完整开发工具链** | 首启额外 `apk add build-base git python3` | 用户开箱要能 `gcc` 编东西。现在得自己装，装的过程还要跟 DNS 代理竞态斗（见 pitfalls）。预装省掉这一整轮。 |
| **Alpine + X11/Wayland 图形栈** | 首启额外 `apk add xvfb xterm x11frsrv 依赖` | 「触摸容器画面」这个功能的前提。不预装的话用户点了「开始画面」只会看到 `Xvfb: not found`。 |

这两个**不占额外磁盘**（共用同一个 rootfs 目录），只是首启时多跑一次 `apk add`。
选项里如实标注「额外下载体积」。

### 2. 第三个发行版：Alpine edge（滚动版）

真正有意义的新发行版选项 —— 与 stable 3.22 的区别是包版本更新（用户要新版
gcc/python 时不必自己改仓库）。**风险要写在 UI 上**：edge 是滚动版，不保证稳定。

### 3. 不做 Debian/Fedora 的理由（写进注释，避免以后有人再来加）

- **Debian**：rootfs 体积 100MB+ 且 CN 镜像对 `debootstrap` 产物不友好；
  关键是它和已支持的 Ubuntu 是同一个 glibc 生态，边际价值低。
- **Fedora/Arch**：镜像在 Android 上要额外处理 `systemd` 依赖残留，且没有
  成熟的小体积 minirootfs —— 投入产出比不如把 Alpine 做好。

## 三、暴露给 AI 的 API

现状：Linux 工作台的能力**完全没暴露**。`EndpointCatalog` 里 `grep -n linux` 是空的，
`AgentBridge` 路由表里也没有任何 `/dev/linux*`。AI 无法知道容器存不存在、装没装、
能切哪个发行版。

新增 5 个端点（都挂在 `cap.linux` 门控下，默认关闭 —— 这是「能改用户容器状态」
的操作，与只读的设备状态查询不同级）：

| 端点 | 作用 | 为什么 AI 需要 |
|---|---|---|
| `GET /dev/linux/status` | 当前发行版 / 是否部署 / 引擎模式 / 磁盘余量 / 已装扩展数 | 「我现在能跑什么」的第一步 |
| `GET /dev/linux/distros` | 全部可选发行版 + 每个的体积/状态/是否激活 | 决定能不能切、切了要下多少 |
| `POST /dev/linux/switch` | 切换激活的发行版（`{"family":"ubuntu"}`） | 用户说「换个环境」时 |
| `POST /dev/linux/deploy` | 部署/重新部署当前发行版（`{"force":false}`） | 修复「未部署」状态 |
| `POST /dev/linux/exec` | **在容器里跑一条命令**并返回 stdout/stderr/rc | 这是真正的价值：AI 能验证环境、装包、跑编译 |

### `exec` 的安全边界（必须写清楚）

- **默认拒绝**：`cap.linux` 关闭时返回 403，且**不改用户的 cap 设置**
- **超时硬上限 120s**：容器里一条命令卡死会占住执行器信号量（上限 3 个并发），
  超时必须杀进程并如实回报 timeout
- **不接受交互式命令**：不做 pty，`read` 之类的命令会立刻 EOF（要在
  `limits` 里写明，避免 AI 以为是自己写错了）
- **不注入宿主环境变量**：`DSH_BRIDGE_TOKEN` 之类绝不进容器（已有实现保证）

## 四、验证方式

- 每项 UI 选项都要有探针断言它**真的在列表里**且 `compressedBytes/extractedBytes`
  是实测值（不是估算 —— UI 拿它跟磁盘余量比）
- API 端点要反向验证：关掉 `cap.linux` 后 `exec` 必须 403 而不是 200
- 门脚本 `dev-linux` 要能被 `dev` 目录自动发现（`EndpointCatalog` 单一数据源）
