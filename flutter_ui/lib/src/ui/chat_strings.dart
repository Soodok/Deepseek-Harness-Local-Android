import 'package:flutter/widgets.dart';

/// Core chat chrome labels sourced from the Android-selected app locale.
/// User-authored messages and Host-provided names remain untouched.
class DshChatLocale extends InheritedWidget {
  const DshChatLocale({
    super.key,
    required this.languageCode,
    required super.child,
  });

  final String languageCode;

  static DshChatLocale? maybeOf(BuildContext context) =>
      context.dependOnInheritedWidgetOfExactType<DshChatLocale>();

  @override
  bool updateShouldNotify(DshChatLocale oldWidget) =>
      oldWidget.languageCode != languageCode;
}

class DshChatStrings {
  const DshChatStrings._(this.languageCode);

  final String languageCode;

  static DshChatStrings of(BuildContext context) =>
      DshChatStrings._(DshChatLocale.maybeOf(context)?.languageCode ?? 'zh');

  String _value(Map<String, String> values) =>
      values[languageCode] ?? values['en']!;

  String get openSessionDrawer => _value({
    'zh': '打开会话抽屉',
    'en': 'Open session drawer',
    'de': 'Sitzungsleiste öffnen',
    'pt': 'Abrir sessões',
    'th': 'เปิดรายการเซสชัน',
    'ru': 'Открыть список сессий',
  });

  String get subagents => _value({
    'zh': '子代理',
    'en': 'Subagents',
    'de': 'Subagenten',
    'pt': 'Subagentes',
    'th': 'ซับเอเจนต์',
    'ru': 'Субагенты',
  });

  String get promptHint => _value({
    'zh': '输入问题、任务或补充说明',
    'en': 'Ask a question, describe a task, or add context',
    'de': 'Frage, Aufgabe oder Zusatzinfo eingeben',
    'pt': 'Digite uma pergunta, tarefa ou contexto',
    'th': 'ป้อนคำถาม งาน หรือรายละเอียดเพิ่มเติม',
    'ru': 'Введите вопрос, задачу или уточнение',
  });

  String get editHint => _value({
    'zh': '修改这条历史消息',
    'en': 'Edit this message',
    'de': 'Diese Nachricht bearbeiten',
    'pt': 'Editar esta mensagem',
    'th': 'แก้ไขข้อความนี้',
    'ru': 'Изменить это сообщение',
  });

  /// Full, self-explanatory name of a built-in preset, matching the DSH Go
  /// strings (featuresPresetStandard / Ptc / Minimal / Creator).
  ///
  /// This is what the menu and the semantics label speak: "标准模式" tells a
  /// first-time reader what the entry is, while a three-character pill does not.
  String agentPresetLabel(String id, String fallback) {
    const labels = <String, Map<String, String>>{
      'standard': {
        'zh': '标准模式',
        'en': 'Standard mode',
        'de': 'Standardmodus',
        'pt': 'Modo padrão',
        'th': 'โหมดมาตรฐาน',
        'ru': 'Стандартный режим',
      },
      'ptc': {
        'zh': 'PTC 模式',
        'en': 'PTC mode',
        'de': 'PTC-Modus',
        'pt': 'Modo PTC',
        'th': 'โหมด PTC',
        'ru': 'Режим PTC',
      },
      'minimal': {
        'zh': '极简模式',
        'en': 'Minimal mode',
        'de': 'Minimalmodus',
        'pt': 'Modo mínimo',
        'th': 'โหมดมินิมอล',
        'ru': 'Минимальный режим',
      },
      'cordis': {
        'zh': '创造模式',
        'en': 'Creator mode',
        'de': 'Kreativmodus',
        'pt': 'Modo criador',
        'th': 'โหมดครีเอเตอร์',
        'ru': 'Творческий режим',
      },
    };
    return labels[id]?[languageCode] ?? labels[id]?['en'] ?? fallback;
  }

  /// The short value a pill can hold whole: the full name minus its trailing
  /// "mode" word. Anything wider belongs in the menu or the semantics label.
  String agentPresetShortLabel(String id, String fallback) {
    const labels = <String, Map<String, String>>{
      'standard': {
        'zh': '标准',
        'en': 'Standard',
        'de': 'Standard',
        'pt': 'Padrão',
        'th': 'มาตรฐาน',
        'ru': 'Стандарт',
      },
      'ptc': {
        'zh': 'PTC',
        'en': 'PTC',
        'de': 'PTC',
        'pt': 'PTC',
        'th': 'PTC',
        'ru': 'PTC',
      },
      'minimal': {
        'zh': '极简',
        'en': 'Minimal',
        'de': 'Minimal',
        'pt': 'Mínimo',
        'th': 'มินิมอล',
        'ru': 'Минимум',
      },
      'cordis': {
        'zh': '创造',
        'en': 'Creator',
        'de': 'Kreativ',
        'pt': 'Criador',
        'th': 'ครีเอเตอร์',
        'ru': 'Творческий',
      },
    };
    // A custom preset keeps the Host's own name; only built-ins are translated.
    return labels[id]?[languageCode] ??
        labels[id]?['en'] ??
        (fallback.isEmpty ? id : fallback);
  }

  /// Pill text: a terse value under a spoken prefix, e.g. 「对话模式：标准」.
  String agentMode(String? id, String? hostLabel) {
    final label = id == null
        ? agentModeUnselected
        : agentPresetShortLabel(id, hostLabel ?? id);
    return _value({
      'zh': '对话模式：$label',
      'en': 'Mode: $label',
      'de': 'Modus: $label',
      'pt': 'Modo: $label',
      'th': 'โหมด: $label',
      'ru': 'Режим: $label',
    });
  }

  /// Full meaning for assistive tech: never the ellipsised pill text.
  String agentModeSemantics(String? id, String? hostLabel) {
    final label = id == null
        ? agentModeUnselected
        : agentPresetLabel(id, hostLabel ?? id);
    return _value({
      'zh': '对话模式：$label，点按切换',
      'en': 'Mode: $label. Tap to change',
      'de': 'Modus: $label. Zum Ändern tippen',
      'pt': 'Modo: $label. Toque para alterar',
      'th': 'โหมด: $label แตะเพื่อเปลี่ยน',
      'ru': 'Режим: $label. Нажмите, чтобы изменить',
    });
  }

  String get selectAgentMode => _value({
    'zh': '选择对话模式',
    'en': 'Select chat mode',
    'de': 'Gesprächsmodus auswählen',
    'pt': 'Selecionar modo de conversa',
    'th': 'เลือกโหมดแชต',
    'ru': 'Выбрать режим чата',
  });

  String get agentModeUnselected => _value({
    'zh': '未选择',
    'en': 'Not selected',
    'de': 'Nicht ausgewählt',
    'pt': 'Não selecionado',
    'th': 'ยังไม่ได้เลือก',
    'ru': 'Не выбран',
  });

  String get agentModeSwitchFailed => _value({
    'zh': '未能切换对话模式',
    'en': 'Could not switch chat mode',
    'de': 'Gesprächsmodus konnte nicht gewechselt werden',
    'pt': 'Não foi possível trocar o modo de conversa',
    'th': 'เปลี่ยนโหมดแชตไม่สำเร็จ',
    'ru': 'Не удалось сменить режим чата',
  });

  /// Workspace capsule: the same short value the picker paints, in app language.
  String workspaceCapsule(String value) => _value({
    'zh': '工作区：$value',
    'en': 'Workspace: $value',
    'de': 'Arbeitsbereich: $value',
    'pt': 'Área de trabalho: $value',
    'th': 'เวิร์กสเปซ: $value',
    'ru': 'Рабочая область: $value',
  });

  String get workspaceHome => _value({
    'zh': 'Home',
    'en': 'Home',
    'de': 'Home',
    'pt': 'Home',
    'th': 'Home',
    'ru': 'Home',
  });

  String get workspaceUnavailable => _value({
    'zh': '工作区暂不可用',
    'en': 'Workspace unavailable',
    'de': 'Arbeitsbereich nicht verfügbar',
    'pt': 'Área de trabalho indisponível',
    'th': 'เวิร์กสเปซไม่พร้อมใช้งาน',
    'ru': 'Рабочая область недоступна',
  });

  String get workspaceStale => _value({
    'zh': '已失效',
    'en': 'Unavailable',
    'de': 'Ungültig',
    'pt': 'Inválida',
    'th': 'ใช้ไม่ได้',
    'ru': 'Недоступно',
  });

  String get selectWorkspace => _value({
    'zh': '选择新对话工作区',
    'en': 'Choose workspace for new chats',
    'de': 'Arbeitsbereich für neue Chats wählen',
    'pt': 'Escolher área de trabalho',
    'th': 'เลือกเวิร์กสเปซสำหรับแชตใหม่',
    'ru': 'Выбрать рабочую область',
  });

  String get pickPrivateDirectory => _value({
    'zh': '选择应用私有目录',
    'en': 'Choose app private folder',
    'de': 'Privaten App-Ordner wählen',
    'pt': 'Escolher pasta privada do app',
    'th': 'เลือกโฟลเดอร์ส่วนตัวของแอป',
    'ru': 'Выбрать приватную папку',
  });

  String get pickSharedDirectory => _value({
    'zh': '选择手机共享目录',
    'en': 'Choose shared phone folder',
    'de': 'Geteilten Telefonordner wählen',
    'pt': 'Escolher pasta compartilhada',
    'th': 'เลือกโฟลเดอร์ที่แชร์ของมือถือ',
    'ru': 'Выбрать общую папку телефона',
  });

  String get retry => _value({
    'zh': '重试',
    'en': 'Retry',
    'de': 'Erneut versuchen',
    'pt': 'Tentar novamente',
    'th': 'ลองใหม่',
    'ru': 'Повторить',
  });

  String get workspaceSaveFailed => _value({
    'zh': '工作区选择未保存，请重试或重新选择',
    'en': 'Workspace choice was not saved. Retry or pick again.',
    'de': 'Auswahl wurde nicht gespeichert. Erneut versuchen oder neu wählen.',
    'pt': 'A escolha não foi salva. Tente novamente.',
    'th': 'ยังไม่ได้บันทึกเวิร์กสเปซ ลองใหม่หรือเลือกอีกครั้ง',
    'ru': 'Выбор не сохранён. Повторите или выберите снова.',
  });

  String toolPermission(String value) => _value({
    'zh': '工具权限：$value',
    'en': 'Tool permission: $value',
    'de': 'Werkzeugberechtigung: $value',
    'pt': 'Permissão de ferramenta: $value',
    'th': 'สิทธิ์เครื่องมือ: $value',
    'ru': 'Разрешение инструмента: $value',
  });

  String get approvalTitle => _value({
    'zh': '需要你确认',
    'en': 'Your confirmation is required',
    'de': 'Deine Bestätigung ist erforderlich',
    'pt': 'É necessária a sua confirmação',
    'th': 'ต้องการการยืนยันจากคุณ',
    'ru': 'Требуется ваше подтверждение',
  });

  String get allowContinue => _value({
    'zh': '允许继续',
    'en': 'Allow and continue',
    'de': 'Erlauben und fortfahren',
    'pt': 'Permitir e continuar',
    'th': 'อนุญาตและดำเนินการต่อ',
    'ru': 'Разрешить и продолжить',
  });

  String get allowOnce => _value({
    'zh': '允许本次',
    'en': 'Allow once',
    'de': 'Einmal erlauben',
    'pt': 'Permitir uma vez',
    'th': 'อนุญาตครั้งเดียว',
    'ru': 'Разрешить один раз',
  });

  String get rejectOnce => _value({
    'zh': '拒绝本次',
    'en': 'Reject once',
    'de': 'Einmal ablehnen',
    'pt': 'Rejeitar uma vez',
    'th': 'ปฏิเสธครั้งเดียว',
    'ru': 'Отклонить один раз',
  });

  String get stop => _value({
    'zh': '停止',
    'en': 'Stop',
    'de': 'Stoppen',
    'pt': 'Parar',
    'th': 'หยุด',
    'ru': 'Остановить',
  });

  String status(String value) => _value({
    'zh': value,
    'en': switch (value) {
      '连接中' => 'Connecting',
      '已连接' => 'Connected',
      '正在回复' => 'Replying',
      '等待确认' => 'Waiting for confirmation',
      '重连中' => 'Reconnecting',
      '连接异常' => 'Connection error',
      _ => value,
    },
    'de': switch (value) {
      '连接中' => 'Verbindung wird hergestellt',
      '已连接' => 'Verbunden',
      '正在回复' => 'Antwort wird erstellt',
      '等待确认' => 'Warten auf Bestätigung',
      '重连中' => 'Wird erneut verbunden',
      '连接异常' => 'Verbindungsfehler',
      _ => value,
    },
    'pt': switch (value) {
      '连接中' => 'Conectando',
      '已连接' => 'Conectado',
      '正在回复' => 'Respondendo',
      '等待确认' => 'Aguardando confirmação',
      '重连中' => 'Reconectando',
      '连接异常' => 'Erro de conexão',
      _ => value,
    },
    'th': switch (value) {
      '连接中' => 'กำลังเชื่อมต่อ',
      '已连接' => 'เชื่อมต่อแล้ว',
      '正在回复' => 'กำลังตอบกลับ',
      '等待确认' => 'รอการยืนยัน',
      '重连中' => 'กำลังเชื่อมต่อใหม่',
      '连接异常' => 'การเชื่อมต่อผิดพลาด',
      _ => value,
    },
    'ru': switch (value) {
      '连接中' => 'Подключение',
      '已连接' => 'Подключено',
      '正在回复' => 'Отвечает',
      '等待确认' => 'Ожидание подтверждения',
      '重连中' => 'Повторное подключение',
      '连接异常' => 'Ошибка подключения',
      _ => value,
    },
  });
}
