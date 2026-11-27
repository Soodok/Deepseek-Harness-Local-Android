enum SettingsDestination {
  models('模型配置', '模型与服务提供商'),
  plugins('插件管理', '管理工具与功能插件'),
  extensions('扩展编译环境', '编译器、SDK 与命令行工具'),
  containers('容器环境选择', '本机运行环境与 Linux 发行版'),
  connections('连接与远程', 'SSH 主机与本机引擎连接'),
  language('语言', '应用显示语言'),
  appearance('个性化主题', '配色、深浅模式与显示'),
  pet('悬浮桌宠', '桌宠开关、权限与资源包'),
  permissions('权限管理', '应用权限与工具访问范围'),
  webUi('切换 Web UI', '打开完整 Web 界面'),
  diagnostics('诊断与日志', '查看运行状态与日志'),
  backup('备份与恢复', '管理本地设置与数据'),
  about('关于', '版本、许可与项目说明');

  const SettingsDestination(this.title, this.subtitle);
  final String title, subtitle;
}

typedef ProviderSettingsSnapshot = ({
  int revision,
  bool writable,
  List<ModelProviderProfile> providers,
});

class ModelProviderProfile {
  const ModelProviderProfile({
    required this.route,
    required this.api,
    required this.baseUrl,
    required this.models,
    this.credentialRef,
  });

  static const protocols = <String>[
    'openai-completions', 'openai-responses', 'anthropic-messages',
  ];
  static final routePattern = RegExp(r'^[a-z][a-z0-9]*(?:-[a-z0-9]+)*$');
  final String route, api, baseUrl;
  final List<String> models;
  final String? credentialRef;
}

class AccountProfile {
  const AccountProfile({
    required this.id,
    required this.displayName,
    this.email = '',
    this.avatarUrl,
  });
  final String id, displayName, email;
  final Uri? avatarUrl;
}

class TokenUsageDay {
  const TokenUsageDay({
    required this.date,
    required this.inputTokens,
    required this.outputTokens,
  });
  final DateTime date;
  final int inputTokens, outputTokens;
  int get totalTokens => inputTokens + outputTokens;
}

class UsageHistory {
  UsageHistory({
    required List<TokenUsageDay> days,
    required this.from,
    required this.through,
    this.sourceLabel = '本机会话',
    this.available = true,
  }) : days = List.unmodifiable(days);
  final List<TokenUsageDay> days;
  final DateTime from, through;
  final String sourceLabel;
  final bool available;
}
