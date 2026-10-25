# DSH Mobile 桌宠素材包（dsh-pet-android 300p）

从 **dsh-pet-android v1.8.1** 的 300p 发布包原样提取，按本 App 的导入器规则重新打包。
不含任何二次绘制，动画作者见 `NOTICE.md`。

| 项 | 值 |
|---|---|
| 文件 | `pet-pack-dsh-pet-300p.zip` |
| 大小 | 76.5 MB（80,202,923 字节） |
| SHA-256 | `61086e9058b74f4f2fe358a4b911529e3bdef5d40be91a8518cada616ec02e80` |
| 动画 | 106 段，300×168，动画 WebP（VP8X + ANIM，约 10.2s/段，15fps） |
| 来源 | https://github.com/Tony-Chen-Stark/dsh-pet-android （上游素材：PC2005-cloud/dsh-pet） |
| 许可 | 素材允许开源使用、**禁止商用**；代码 MIT |

## 导入

1. 把 zip 放到手机上（本机模拟器里已在 `/sdcard/Download/pet-pack-dsh-pet-300p.zip`）。
2. App → 设置 → 扩展 → 桌面宠物 → **导入动画包（APK/zip）** → 选中该 zip。
3. 导入完会显示「106 段动画」；开启「开启桌宠」并授予悬浮窗权限。

导入器只取包里的 `pet-config.json` 与 `webp/*.webp`，`LICENSE` / `NOTICE.md` 会被忽略，
放在包里只是为了传递许可信息。

## 相对上游 config 改了什么

只改了两处，都是为了适配本 App 的语义：

1. `animations.events.workStatus`：上游没有这一段，本 App 用它映射六个工作状态槽位
   （0 思考 / 1 工作 / 2 单步完成 / 3 搜索 / 4 庆祝 / 5 出错）。按动画名解析为
   `a050 a047 a048 a051 a049 a046`（v1.8.1 的编号与旧版不同，不能照抄旧 slug）。
2. `durations`：上游是 151 帧 / 10066ms 的常量；改成从 WebP 实测的帧时长之和 10217ms，
   让「动画播完」与 App 的回收时间对齐。

其余 `names` / `idle` / `clicks` / `categories` / `pets` 全部原样保留。

## 重新生成（480p / 640p）

```bash
# 先下对应 APK（480p 约 290MB，640p 约 445MB）
python scripts/pet-pack-from-apk.py --apk dsh-pet-v1.8.1-480p-24fps.apk --out dist --label 480p
```

脚本会重新校验：zip 路径安全、`webp/` 层级、idle/clicks 至少各有一段、workStatus 与
分类里的 slug 都存在、每段都是动画 WebP、duration 与实测一致。
