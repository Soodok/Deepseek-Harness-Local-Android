import 'dart:async';
import 'dart:convert';

import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:material_ui/material_ui.dart' hide StepState;
import 'package:dsh_native_chat/src/model/tool_content.dart';
import 'package:dsh_native_chat/src/theme/dsh_theme.dart';
import 'package:dsh_native_chat/src/ui/tool_content_view.dart';

const _file = OutputResource(
  id: 'report',
  title: '验证报告.md',
  uri: 'https://example.test/report.md',
  kind: ResourceKind.file,
  mediaType: 'text/markdown',
  byteSize: 2048,
);
const _image = OutputResource(
  id: 'image',
  title: '界面截图',
  uri: 'https://example.test/screenshot.png',
  kind: ResourceKind.image,
  mediaType: 'image/png',
);

Finder _key(String value) => find.byKey(ValueKey(value));

Future<void> _pump(
  WidgetTester tester,
  ToolContent content, {
  Future<bool> Function(OutputResource)? onOpenResource,
  AssetBundle? bundle,
  double textScale = 1,
}) async {
  await tester.pumpWidget(
    MaterialApp(
      theme: DshTheme.light(),
      home: DefaultAssetBundle(
        bundle: bundle ?? rootBundle,
        child: Scaffold(
          body: Builder(
            builder: (context) => MediaQuery(
              data: MediaQuery.of(context)
                  .copyWith(textScaler: TextScaler.linear(textScale)),
              child: SingleChildScrollView(
                padding: const EdgeInsets.all(20),
                child: ToolContentView(
                  content: content,
                  onOpenResource: onOpenResource,
                ),
              ),
            ),
          ),
        ),
      ),
    ),
  );
  await tester.pump();
}

void main() {
  for (final width in [360.0, 390.0, 432.0]) {
    testWidgets('production theme fits $width with 48dp resource actions', (
      tester,
    ) async {
      tester.view.physicalSize = Size(width, 960);
      tester.view.devicePixelRatio = 1;
      addTearDown(tester.view.resetPhysicalSize);
      addTearDown(tester.view.resetDevicePixelRatio);
      final longTitle = List.filled(12, '较长的结构化内容').join();
      await _pump(
        tester,
        ToolContent(
          title: longTitle,
          steps: [
            PlanStep(
              id: 'running',
              title: longTitle,
              status: StepState.running,
            ),
          ],
          agents: [AgentTask(id: 'agent', name: longTitle, task: longTitle)],
          resources: [
            OutputResource(
              id: 'image',
              title: longTitle,
              uri: _image.uri,
              kind: ResourceKind.image,
              mediaType: 'image/png',
              byteSize: 2048,
            ),
          ],
        ),
        onOpenResource: (_) async => true,
        textScale: 1.3,
      );
      for (final name in [
        'resource-open-image',
        'resource-copy-image',
        'resource-preview-image',
      ]) {
        final action = _key(name);
        await tester.ensureVisible(action);
        await tester.pumpAndSettle();
        final rect = tester.getRect(action);
        expect(rect.width, greaterThanOrEqualTo(48));
        expect(rect.height, greaterThanOrEqualTo(48));
        expect(rect.left, greaterThanOrEqualTo(0));
        expect(rect.right, lessThanOrEqualTo(width));
        expect(action.hitTestable(), findsOneWidget);
      }
      await tester.ensureVisible(_key('tool-plan'));
      await tester.tap(_key('tool-plan'));
      await tester.pumpAndSettle();
      await tester.ensureVisible(_key('tool-agent-agent'));
      await tester.tap(_key('tool-agent-agent'));
      await tester.pumpAndSettle();
      expect(tester.takeException(), isNull);
    });
  }

  testWidgets('plan starts quiet and expands all step states', (tester) async {
    await _pump(
      tester,
      ToolContent(
        title: '检查项目',
        steps: [
          for (final state in StepState.values)
            PlanStep(id: state.name, title: '步骤-' + state.name, status: state),
        ],
      ),
    );
    expect(find.text('计划 · 1/5 已完成'), findsOneWidget);
    expect(find.text('当前：步骤-running'), findsOneWidget);
    expect(_key('plan-step-pending'), findsNothing);
    await tester.tap(_key('tool-plan'));
    await tester.pumpAndSettle();
    for (final state in StepState.values) {
      final row = _key('plan-step-' + state.name);
      expect(row, findsOneWidget);
      expect(
        find.descendant(of: row, matching: find.byType(Icon)),
        findsOneWidget,
      );
    }
    await tester.tap(find.text('计划 · 1/5 已完成'));
    await tester.pumpAndSettle();
    expect(_key('plan-step-pending'), findsNothing);
  });

  testWidgets('agents show every status before expanding task and result', (
    tester,
  ) async {
    const labels = ['运行中', '等待中', '已完成', '失败', '已取消'];
    await _pump(
      tester,
      ToolContent(
        title: '并行检查',
        agents: [
          for (final state in AgentState.values)
            AgentTask(
              id: state.name,
              name: '代理-' + state.name,
              task: '独立任务-' + state.name,
              detail: '执行结果-' + state.name,
              status: state,
            ),
        ],
      ),
    );
    for (final label in labels) {
      expect(find.text(label), findsOneWidget);
    }
    expect(find.text('独立任务-running'), findsNothing);
    expect(find.text('执行结果-running'), findsNothing);
    await tester.tap(_key('tool-agent-running'));
    await tester.pumpAndSettle();
    expect(find.text('独立任务-running'), findsOneWidget);
    expect(find.text('执行结果-running'), findsOneWidget);
  });

  testWidgets('open receives exact resource without invented success', (
    tester,
  ) async {
    OutputResource? received;
    await _pump(
      tester,
      ToolContent(title: '产物', resources: [_file]),
      onOpenResource: (resource) async {
        received = resource;
        return true;
      },
    );
    expect(find.textContaining('2 KB'), findsOneWidget);
    await tester.tap(_key('resource-open-report'));
    await tester.pumpAndSettle();
    expect(identical(received, _file), isTrue);
    expect(find.textContaining('打开失败'), findsNothing);
    expect(find.text('打开成功'), findsNothing);
  });

  for (final throws in [false, true]) {
    testWidgets('open failure stays visible and URI can be copied ($throws)', (
      tester,
    ) async {
      String? copied;
      tester.binding.defaultBinaryMessenger.setMockMethodCallHandler(
        SystemChannels.platform,
        (call) async {
          if (call.method == 'Clipboard.setData')
            copied = (call.arguments as Map)['text'] as String;
          return null;
        },
      );
      addTearDown(
        () => tester.binding.defaultBinaryMessenger.setMockMethodCallHandler(
          SystemChannels.platform,
          null,
        ),
      );
      await _pump(
        tester,
        ToolContent(title: '产物', resources: [_file]),
        onOpenResource: (_) async {
          if (throws) throw StateError('host unavailable');
          return false;
        },
      );
      await tester.tap(_key('resource-open-report'));
      await tester.pumpAndSettle();
      expect(find.text('打开失败，请复制链接后重试。'), findsOneWidget);
      await tester.tap(_key('resource-copy-report'));
      await tester.pumpAndSettle();
      expect(copied, _file.openableUri.toString());
      expect(find.text('已复制'), findsOneWidget);
      expect(find.text('打开失败，请复制链接后重试。'), findsOneWidget);
    });
  }

  testWidgets('open is disabled without host callback', (tester) async {
    await _pump(tester, ToolContent(title: '产物', resources: [_file]));
    expect(
      tester.widget<TextButton>(_key('resource-open-report')).onPressed,
      isNull,
    );
    expect(
      tester.widget<TextButton>(_key('resource-copy-report')).onPressed,
      isNotNull,
    );
    expect(find.text('此环境暂不支持打开，请复制链接。'), findsOneWidget);
  });

  testWidgets('pending open cannot be dispatched twice', (tester) async {
    final result = Completer<bool>();
    var calls = 0;
    await _pump(
      tester,
      ToolContent(title: '产物', resources: [_file]),
      onOpenResource: (_) {
        calls++;
        return result.future;
      },
    );
    await tester.tap(_key('resource-open-report'));
    await tester.pump();
    expect(
      tester.widget<TextButton>(_key('resource-open-report')).onPressed,
      isNull,
    );
    await tester.tap(_key('resource-open-report'));
    expect(calls, 1);
    result.complete(true);
    await tester.pumpAndSettle();
    expect(
      tester.widget<TextButton>(_key('resource-open-report')).onPressed,
      isNotNull,
    );
  });

  for (final uri in [
    'javascript:alert(1)',
    'file:///secret.png',
    'data:image/png;base64,AA==',
    'https://user:pass@example.test/a',
  ]) {
    testWidgets('unsafe URI actions are disabled: $uri', (tester) async {
      var calls = 0;
      await _pump(
        tester,
        ToolContent(
          title: '不安全资源',
          resources: [
            OutputResource(
              id: 'unsafe',
              title: '资源',
              uri: uri,
              kind: ResourceKind.image,
            ),
          ],
        ),
        onOpenResource: (_) async {
          calls++;
          return true;
        },
      );
      for (final name in ['open', 'copy', 'preview']) {
        expect(
          tester
              .widget<TextButton>(_key('resource-' + name + '-unsafe'))
              .onPressed,
          isNull,
        );
      }
      expect(find.text('链接不可用'), findsOneWidget);
      expect(find.byType(Image), findsNothing);
      expect(calls, 0);
    });
  }

  testWidgets('stale open result cannot mark a replacement resource failed', (
    tester,
  ) async {
    final result = Completer<bool>();
    await _pump(
      tester,
      ToolContent(title: '产物', resources: [_file]),
      onOpenResource: (_) => result.future,
    );
    await tester.tap(_key('resource-open-report'));
    await tester.pump();
    const replacement = OutputResource(
      id: 'report',
      title: '新版报告',
      uri: 'https://example.test/new-report.md',
      kind: ResourceKind.file,
    );
    await _pump(
      tester,
      ToolContent(title: '产物', resources: [replacement]),
      onOpenResource: (_) async => true,
    );
    result.complete(false);
    await tester.pumpAndSettle();
    expect(find.text('新版报告'), findsOneWidget);
    expect(find.textContaining('打开失败'), findsNothing);
    expect(
      tester.widget<TextButton>(_key('resource-open-report')).onPressed,
      isNotNull,
    );
  });

  testWidgets('clipboard failure is visible without false copied feedback', (
    tester,
  ) async {
    tester.binding.defaultBinaryMessenger.setMockMethodCallHandler(
      SystemChannels.platform,
      (call) async {
        if (call.method == 'Clipboard.setData')
          throw PlatformException(code: 'denied');
        return null;
      },
    );
    addTearDown(
      () => tester.binding.defaultBinaryMessenger.setMockMethodCallHandler(
        SystemChannels.platform,
        null,
      ),
    );
    await _pump(tester, ToolContent(title: '产物', resources: [_file]));
    await tester.tap(_key('resource-copy-report'));
    await tester.pumpAndSettle();
    expect(find.text('复制失败，请重试。'), findsOneWidget);
    expect(find.text('已复制'), findsNothing);
  });

  testWidgets('image builds only after preview with loading and error UI', (
    tester,
  ) async {
    tester.view.physicalSize = const Size(4320, 2880);
    tester.view.devicePixelRatio = 3;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);
    await _pump(tester, ToolContent(title: '图片', resources: [_image]));
    expect(find.byType(Image), findsNothing);
    expect(find.byType(InteractiveViewer), findsNothing);
    await tester.tap(_key('resource-preview-image'));
    await tester.pumpAndSettle();
    final finder = _key('resource-image-image');
    final image = tester.widget<Image>(finder);
    expect(image.image, isA<ResizeImage>());
    final preview = image.image as ResizeImage;
    expect(preview.policy, ResizeImagePolicy.fit);
    expect(preview.width, lessThanOrEqualTo(1600));
    expect(preview.height, lessThanOrEqualTo(1600));
    expect(
      (preview.imageProvider as NetworkImage).url,
      _image.openableUri.toString(),
    );
    expect(image.loadingBuilder, isNotNull);
    expect(image.errorBuilder, isNotNull);
    expect(find.byType(InteractiveViewer), findsOneWidget);
    final loading = image.loadingBuilder!(
      tester.element(finder),
      const SizedBox(),
      const ImageChunkEvent(cumulativeBytesLoaded: 1, expectedTotalBytes: 2),
    );
    final error = image.errorBuilder!(
      tester.element(finder),
      StateError('offline'),
      StackTrace.empty,
    );
    await tester.ensureVisible(_key('resource-preview-close-image'));
    await tester.tap(_key('resource-preview-close-image'));
    await tester.pumpAndSettle();
    expect(find.byType(Image), findsNothing);
    await tester.pumpWidget(
      MaterialApp(
        theme: DshTheme.light(),
        home: Scaffold(body: loading),
      ),
    );
    expect(find.text('正在加载图片…'), findsOneWidget);
    await tester.pumpWidget(
      MaterialApp(
        theme: DshTheme.light(),
        home: Scaffold(body: error),
      ),
    );
    expect(find.text('图片加载失败，请关闭预览后重试或复制链接。'), findsOneWidget);
  });

  testWidgets('trusted package asset loads only after preview', (tester) async {
    final bundle = _ImageBundle();
    const resource = OutputResource(
      id: 'asset',
      title: '离线演示图',
      uri: '',
      kind: ResourceKind.image,
      assetPath: 'assets/demo.png',
    );
    await _pump(
      tester,
      ToolContent(title: '演示', resources: [resource]),
      bundle: bundle,
    );
    expect(bundle.imageLoads, 0);
    expect(find.byType(Image), findsNothing);
    await tester.tap(_key('resource-preview-asset'));
    await tester.pumpAndSettle();
    final image = tester.widget<Image>(_key('resource-image-asset'));
    expect(image.image, isA<ResizeImage>());
    final preview = image.image as ResizeImage;
    expect(preview.policy, ResizeImagePolicy.fit);
    expect(preview.width, lessThanOrEqualTo(1600));
    expect(preview.height, lessThanOrEqualTo(1600));
    final provider = preview.imageProvider as AssetImage;
    expect(provider.assetName, 'assets/demo.png');
    expect(provider.package, 'dsh_native_chat');
    expect(bundle.imageLoads, 1);
    expect(find.byType(InteractiveViewer), findsOneWidget);
    await tester.ensureVisible(_key('resource-preview-close-asset'));
    await tester.tap(_key('resource-preview-close-asset'));
    await tester.pumpAndSettle();
    expect(find.byType(Image), findsNothing);
  });
}

class _ImageBundle extends CachingAssetBundle {
  int imageLoads = 0;

  @override
  Future<ByteData> load(String key) async {
    if (key == 'AssetManifest.bin') {
      return const StandardMessageCodec().encodeMessage(<String, Object?>{})!;
    }
    if (key == 'packages/dsh_native_chat/assets/demo.png') {
      imageLoads++;
      return ByteData.sublistView(
        base64Decode(
          'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAACklEQVR4nGMAAQAABQABDQottAAAAABJRU5ErkJggg==',
        ),
      );
    }
    throw StateError('Unexpected asset: $key');
  }
}
