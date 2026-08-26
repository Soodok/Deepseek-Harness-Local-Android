# dsh-android 混淆规则
# PTY JNI 通过 RegisterNatives 以命名约定绑定，保持不被裁剪
-keep class app.dsh.mobile.engine.Pty { *; }
