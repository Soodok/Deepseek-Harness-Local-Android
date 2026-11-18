import 'dart:async';
import 'dart:typed_data';

import 'package:material_ui/material_ui.dart';
import 'package:flutter/scheduler.dart';

import '../bridge/host_bridge.dart';

/// 原生对话页的入场淡化：**Flutter 自己画那张底图，再自己淡掉它**。
///
/// 为什么不交给宿主窗口做（beta.53～beta.70 十几轮录屏的结论）：
/// 覆盖层是独立子窗口、Flutter 画在另一个 Surface 上，两者之间没有可用的混合过渡。
/// 底图一开始淡，底下露出来的是黑（k10.mp4 帧 193 亮度 18.2），随后一帧跳到 233.8；
/// 窗口 alpha / TextureView / 逐帧 Ticker / 手写台阶全都试过，都是同一结果。
///
/// 现在改成两层同一个像素内容：宿主先把底图盖在最上面撑住冷启动，Flutter 起来后
/// 在自己这层画同一张图（视觉上完全一样，撤掉覆盖层看不出跳变），然后由**同一个
/// 合成器**把这张图淡掉，露出下面 Flutter 已经画好的那一帧。
///
/// 注意淡出来的第一屏不一定是会话页：冷启动时 Flutter 先画的是「本机引擎 /
/// 正在准备对话」这一屏，会话页要再等几秒才换上（k29.mp4 里淡出过程露出的是
/// 占位页，几秒后才出现带消息的会话页）。淡化解决的是「不要一帧硬切」，
/// 不是「一次直接切到会话页」。
class LaunchFade extends StatefulWidget {
  const LaunchFade({super.key, required this.child, this.backdrop});

  final Widget child;

  /// 宿主拍下的 Web 那一屏；null 表示没有底图（Web 预览、拍图失败）。
  final Uint8List? backdrop;

  /// 淡出分几步。
  ///
  /// 按**帧数**走而不是按时间走：冷启动那几秒 Flutter 只有 3～5 帧/秒，
  /// AnimationController 按墙钟算进度，第一个 tick 到达时已经 4 秒过去，
  /// 750ms 的动画在第一帧就折算成 1.0 —— 屏幕上于是仍然是一帧硬切
  /// （beta.73 k25.mp4、beta.75 k27.mp4 都是这个形状）。
  static const int steps = 12;

  @override
  State<LaunchFade> createState() => _LaunchFadeState();
}

class _LaunchFadeState extends State<LaunchFade>
    with SingleTickerProviderStateMixin {
  late final Ticker _ticker = createTicker(_onTick);

  double _opacity = 1;
  int _elapsedSteps = 0;
  bool _started = false;

  @override
  void initState() {
    super.initState();
    if (widget.backdrop == null) {
      _opacity = 0;
    } else {
      // 等这一帧（含底图）真的画上去，再撤宿主的覆盖层：两层是同一个像素内容，
      // 撤掉那一刻看不出任何跳变。撤完才开始淡，否则会先露出还没上屏的黑。
      WidgetsBinding.instance.addPostFrameCallback((_) {
        if (!mounted) return;
        unawaited(_handOver());
      });
    }
  }

  /// 先把图解码进缓存，再撤宿主的覆盖层，最后开始淡。
  ///
  /// 顺序不能换，每一步都有实测依据：
  /// · Image.memory 是异步解码的，直接撤层会先露出「等待本机引擎就绪」的空白页
  ///   （beta.74 k26.mp4 帧 107：32.0 一帧跳到 226.7 的空白页，之后才 233.8）；
  /// · 撤层走主线程消息，冷启动那几秒主线程被引擎占着，实测要 4 秒才执行，
  ///   所以必须**等它执行完**再开始淡（beta.72 k23.mp4：Dart 420ms 就淡完，
  ///   Java 到 39.6 秒才真正撤层，屏幕上仍是一帧硬切）。
  Future<void> _handOver() async {
    final backdrop = widget.backdrop;
    if (backdrop == null) return;
    try {
      await precacheImage(MemoryImage(backdrop), context)
          .timeout(const Duration(milliseconds: 2500));
    } catch (_) {
      // 解码失败也要往下走：宁可少一次淡化，也不能把底图永远盖着。
    }
    if (!mounted) return;
    // 解码后还要再等一帧把图真的画上去，然后才能撤宿主那层。
    await WidgetsBinding.instance.endOfFrame;
    if (!mounted) return;
    try {
      await HostBridge.releaseBackdrop()
          .timeout(const Duration(milliseconds: 1500));
    } catch (_) {
      // 宿主没应答也要往下走。
    }
    if (!mounted) return;
    _play();
  }

  void _play() {
    if (_started || !mounted) return;
    _started = true;
    if (MediaQuery.maybeDisableAnimationsOf(context) ?? false) {
      setState(() => _opacity = 0);
      return;
    }
    _ticker.start();
  }

  void _onTick(Duration _) {
    // 每个渲染帧退一步，与真实时间无关：帧率再低也一定是「逐帧变淡」，
    // 不会出现折算成 1.0 的一次性跳变。
    _elapsedSteps++;
    final progress = (_elapsedSteps / LaunchFade.steps).clamp(0.0, 1.0);
    setState(() => _opacity = 1 - progress);
    if (progress >= 1) _ticker.stop();
  }

  @override
  void dispose() {
    _ticker.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final backdrop = widget.backdrop;
    if (backdrop == null) return widget.child;
    // 显式给非方向性 alignment：这层挂在 MaterialApp.builder 上，Web 预览与单测未必有 Directionality。
    return Stack(
      alignment: Alignment.topLeft,
      fit: StackFit.expand,
      children: [
        widget.child,
        // 底图不接收触摸：它只是一层视觉过渡，淡完就没了。
        IgnorePointer(
          child: Opacity(
            opacity: _opacity,
            child: Image.memory(
              backdrop,
              fit: BoxFit.cover,
              gaplessPlayback: true,
            ),
          ),
        ),
      ],
    );
  }
}
