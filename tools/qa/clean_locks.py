import os
import shutil

lock_dir = r"scratch\ui-review\avd-home-api35\UHabit_UI_Review_Disposable_20261011.avd"
if os.path.exists(lock_dir):
    for name in os.listdir(lock_dir):
        if name.endswith(".lock"):
            full_path = os.path.join(lock_dir, name)
            if os.path.isdir(full_path):
                shutil.rmtree(full_path, ignore_errors=True)
            else:
                try:
                    os.remove(full_path)
                except Exception:
                    pass
            print(f"Removed {full_path}")
