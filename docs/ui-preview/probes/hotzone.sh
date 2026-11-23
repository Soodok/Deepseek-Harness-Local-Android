export PATH="/e/Android/Sdk/platform-tools:$PATH"
cd "E:/Deepeek harness/dsh-android"
for y in 100 120 132 145 175 210 218 230 250; do
  MSYS_NO_PATHCONV=1 adb -s emulator-5556 shell input keyevent KEYCODE_BACK >/dev/null 2>&1
  sleep 1
  MSYS_NO_PATHCONV=1 adb -s emulator-5556 shell am force-stop app.dsh.mobile >/dev/null
  MSYS_NO_PATHCONV=1 adb -s emulator-5556 shell am start -n app.dsh.mobile/.OnboardingActivity >/dev/null 2>&1
  sleep 5
  MSYS_NO_PATHCONV=1 adb -s emulator-5556 shell input tap 924 $y >/dev/null
  sleep 2
  MSYS_NO_PATHCONV=1 adb -s emulator-5556 exec-out screencap -p > _tmp_shots/hz.png
  echo -n "y=$y -> "
  PYTHONIOENCODING=utf-8 python _tmp_shots/px.py _tmp_shots/hz.png
done