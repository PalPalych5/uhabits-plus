import sqlite3
import subprocess
import sys
import json
import hashlib
import os

def compute_sha256(filepath):
    h = hashlib.sha256()
    with open(filepath, "rb") as f:
        while chunk := f.read(65536):
            h.update(chunk)
    return h.hexdigest()

def inspect_database(db_path):
    conn = sqlite3.connect(db_path)
    cursor = conn.cursor()

    try:
        cursor.execute("PRAGMA wal_checkpoint(TRUNCATE)")
    except Exception:
        pass

    user_version = cursor.execute("PRAGMA user_version").fetchone()[0]
    integrity = cursor.execute("PRAGMA integrity_check").fetchone()[0]

    cursor.execute("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' AND name NOT LIKE 'android_%' ORDER BY name")
    tables = [row[0] for row in cursor.fetchall()]

    counts = {}
    for t in tables:
        try:
            cnt = cursor.execute(f'SELECT count(*) FROM "{t}"').fetchone()[0]
            counts[t] = cnt
        except Exception as e:
            counts[t] = f"missing/error: {e}"

    habits = []
    try:
        for row in cursor.execute("SELECT id, name, type, color, freq_num, freq_den, archived FROM Habits ORDER BY id").fetchall():
            habits.append({
                "id": row[0], "name": row[1], "type": row[2], "color": row[3],
                "freq_num": row[4], "freq_den": row[5], "archived": row[6]
            })
    except Exception:
        pass

    containers = []
    try:
        for row in cursor.execute("SELECT id, name, parent_id FROM Containers ORDER BY id").fetchall():
            containers.append({"id": row[0], "name": row[1], "parent_id": row[2]})
    except Exception:
        pass

    tasks = []
    try:
        for row in cursor.execute("SELECT id, title, status, container_id, linked_habit_uuid, habit_time_accrual_enabled FROM UserTasks ORDER BY id").fetchall():
            tasks.append({
                "id": row[0], "title": row[1], "status": row[2], "container_id": row[3],
                "linked_habit_uuid": row[4], "accrual_enabled": row[5]
            })
    except Exception:
        pass

    focus_sessions = []
    try:
        for row in cursor.execute("SELECT id, started_at, ended_at, duration_ms, primary_target_type, task_id, habit_uuid, container_id FROM FocusSessions ORDER BY id").fetchall():
            focus_sessions.append({
                "id": row[0], "started_at": row[1], "ended_at": row[2], "duration_ms": row[3],
                "primary_target_type": row[4], "task_id": row[5], "habit_uuid": row[6], "container_id": row[7]
            })
    except Exception:
        pass

    contributions = []
    try:
        for row in cursor.execute("SELECT id, target_habit_uuid, contributed_value, source_type, source_id, removed_at FROM Contributions ORDER BY id").fetchall():
            contributions.append({
                "id": row[0], "target_habit_uuid": row[1], "contributed_value": row[2],
                "source_type": row[3], "source_id": row[4], "removed_at": row[5]
            })
    except Exception:
        pass

    conn.close()

    return {
        "file": os.path.basename(db_path),
        "size": os.path.getsize(db_path),
        "sha256": compute_sha256(db_path),
        "user_version": user_version,
        "integrity": integrity,
        "table_counts": counts,
        "sample_habits": habits,
        "sample_containers": containers,
        "sample_tasks": tasks,
        "sample_focus_sessions": focus_sessions,
        "sample_contributions": contributions
    }

def pull_and_inspect(adb_path, serial, package, db_name, dest_path, output_json):
    cmd = [adb_path, "-s", serial, "exec-out", "run-as", package, "cat", f"databases/{db_name}"]
    with open(dest_path, "wb") as f:
        res = subprocess.run(cmd, stdout=f, stderr=subprocess.PIPE)
        if res.returncode != 0:
            raise RuntimeError(f"exec-out failed: {res.stderr.decode()}")

    for ext in ["-wal", "-shm"]:
        target_sidecar = f"{dest_path}{ext}"
        cmd_sidecar = [adb_path, "-s", serial, "exec-out", "run-as", package, "cat", f"databases/{db_name}{ext}"]
        with open(target_sidecar, "wb") as sf:
            subprocess.run(cmd_sidecar, stdout=sf, stderr=subprocess.PIPE)
        if os.path.exists(target_sidecar) and os.path.getsize(target_sidecar) == 0:
            os.remove(target_sidecar)

    result = inspect_database(dest_path)
    with open(output_json, "w", encoding="utf-8") as f:
        json.dump(result, f, indent=2, ensure_ascii=False)
    print(f"Pulled and analyzed {db_name} -> {output_json}")
    return result

if __name__ == "__main__":
    if len(sys.argv) >= 7 and sys.argv[1] == "pull":
        # Usage: inspect_db.py pull <adb_path> <serial> <package> <db_name> <dest_path> <output_json>
        pull_and_inspect(sys.argv[2], sys.argv[3], sys.argv[4], sys.argv[5], sys.argv[6], sys.argv[7])
    elif len(sys.argv) >= 2:
        db_path = sys.argv[1]
        result = inspect_database(db_path)
        if len(sys.argv) >= 3:
            with open(sys.argv[2], "w", encoding="utf-8") as f:
                json.dump(result, f, indent=2, ensure_ascii=False)
            print(f"Saved database analysis to {sys.argv[2]}")
        else:
            print(json.dumps(result, indent=2, ensure_ascii=False))
    else:
        print("Usage: inspect_db.py <path_to_db> [output_json]")
        print("   or: inspect_db.py pull <adb_path> <serial> <package> <db_name> <dest_path> <output_json>")
        sys.exit(1)
