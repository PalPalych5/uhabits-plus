import subprocess
import os

adb = "adb"
serial = "emulator-5580"

def adb_cmd(args):
    return subprocess.run([adb, "-s", serial] + args, capture_output=True, text=True)

print("Installing debug APK...")
r1 = adb_cmd(["install", "-r", r"uhabits-android\build\outputs\apk\debug\uhabits-android-debug.apk"])
print("App install:", r1.stdout.strip(), r1.stderr.strip())

print("Installing test APK...")
r2 = adb_cmd(["install", "-r", r"uhabits-android\build\outputs\apk\androidTest\debug\uhabits-android-debug-androidTest.apk"])
print("Test install:", r2.stdout.strip(), r2.stderr.strip())

# Grant permissions and configure emulator
adb_cmd(["shell", "-n", "-T", "pm grant org.isoron.uhabits.plus android.permission.POST_NOTIFICATIONS"])
adb_cmd(["shell", "-n", "-T", "input keyevent KEYCODE_WAKEUP"])
adb_cmd(["shell", "-n", "-T", "wm dismiss-keyguard"])
for scale in ["window_animation_scale", "transition_animation_scale", "animator_duration_scale"]:
    adb_cmd(["shell", "-n", "-T", f"settings put global {scale} 0"])

print("Ready!")
