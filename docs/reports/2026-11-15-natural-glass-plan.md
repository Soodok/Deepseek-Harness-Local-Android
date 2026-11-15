# 自然配色与共享会话布局

基线9ba99ffec2ce002c84e6e7417ede8e3eb07af1f1；仅隔离树E:/tmp/wt-flutter-native-chat。

- [x] 验证liquid_glass_widgets 1.17.3、DSH子会话与DSH Go参考。
- [x] 雾灰、暖砂、鼠尾草、暮紫四套深浅配色，通过ThemeExtension传递，抽屉切换，不重建会话。
- [x] GlassPanel使用GlassContainer，去掉厚边和重阴影，减少动效或高对比使用平静材质。
- [x] 主子会话共享消息渲染、居中宽度、悬浮顶栏和输入框；一次性/未知会话只读，按当前transport已支持的能力提供续聊，不添加模型、附件、权限或停止空入口。
- [x] OverflowTitle溢出才滚动、两端停顿、减少动效和后台暂停、完整无障碍标题。
- [x] 红绿测试、串行analyze/Web、多配色实际截图与独立复核完成，交接已更新；隔离提交收尾。
