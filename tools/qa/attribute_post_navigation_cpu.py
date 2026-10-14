"""CPU and runnable overlap for exact accepted-input to presented-frame windows."""
import argparse
import csv
import io
import json
import subprocess
import sys
from pathlib import Path

def main():
    p = argparse.ArgumentParser()
    p.add_argument('--source', type=Path, required=True)
    p.add_argument('--processor', type=Path, required=True)
    p.add_argument('--output', type=Path, help='Choose a new path to repeat analysis without replacing evidence')
    args = p.parse_args()
    stem = args.source.with_suffix('')
    source = json.loads(args.source.read_text(encoding='utf-8-sig'))
    presentation = json.loads(Path(str(stem) + '-accepted-presentation.json').read_text())
    values = ','.join('(%d,%d)' % (o['ordinal'], o['surfaceFrameToken'])
        for o in presentation['operations'] if o['status'] == 'linked')
    if not values:
        raise RuntimeError('No exact windows')
    sql = f"""
    WITH ops(op,token) AS (VALUES {values}),
    app AS (SELECT upid FROM process WHERE pid={int(source['pid'])}),
    windows AS MATERIALIZED (
      SELECT op,i.ts lo,f.ts+f.dur hi FROM ops
      JOIN slice i ON i.name='habit.op.accepted.'||op
      JOIN thread_track tt ON tt.id=i.track_id JOIN thread t ON t.utid=tt.utid
      JOIN actual_frame_timeline_slice f ON f.surface_frame_token=token AND f.upid=t.upid
      WHERE t.upid IN (SELECT upid FROM app) AND f.dur>=0
        AND f.layer_name GLOB '*org.isoron.uhabits.plus/org.isoron.uhabits.activities.main.MainActivity*'
        AND f.present_type IN ('On-time Present','Early Present','Late Present')
    ), app_sched AS MATERIALIZED (
      SELECT s.*,t.name,t.is_main_thread FROM sched s JOIN thread t ON t.utid=s.utid
      WHERE t.upid IN (SELECT upid FROM app) AND s.dur>=0
    ), app_state AS MATERIALIZED (
      SELECT s.*,t.name,t.is_main_thread FROM thread_state s JOIN thread t ON t.utid=s.utid
      WHERE t.upid IN (SELECT upid FROM app) AND s.dur>=0 AND s.state IN ('R','R+')
    ), running AS (
      SELECT w.op,s.name,s.is_main_thread,
        SUM(MIN(s.ts+s.dur,w.hi)-MAX(s.ts,w.lo))/1e6 ms
      FROM windows w JOIN app_sched s ON s.ts<w.hi AND s.ts+s.dur>w.lo
      GROUP BY w.op,s.utid
    ), runnable AS (
      SELECT w.op,s.name,s.is_main_thread,
        SUM(MIN(s.ts+s.dur,w.hi)-MAX(s.ts,w.lo))/1e6 ms
      FROM windows w JOIN app_state s ON s.ts<w.hi AND s.ts+s.dur>w.lo
      GROUP BY w.op,s.utid
    )
    SELECT 'cpu' kind,hex(json_object('operation',op,'thread',name,'main',is_main_thread,'ms',ms)) payload FROM running
    UNION ALL SELECT 'runnable',hex(json_object('operation',op,'thread',name,'main',is_main_thread,'ms',ms)) FROM runnable
    UNION ALL SELECT 'runtimeSliceOverlap',hex(json_object('operation',w.op,'id',s.id,'parent',s.parent_id,
      'name',s.name,'thread',t.name,'overlapMs',(MIN(s.ts+s.dur,w.hi)-MAX(s.ts,w.lo))/1e6))
      FROM windows w JOIN slice s ON s.ts<w.hi AND s.ts+s.dur>w.lo AND s.dur>=0
      JOIN thread_track tt ON tt.id=s.track_id JOIN thread t ON t.utid=tt.utid
      WHERE t.upid IN (SELECT upid FROM app) AND
        (s.name GLOB '*GC*' OR s.name GLOB '*JIT*' OR s.name GLOB '*Jit*' OR s.name GLOB '*Compile*')
    UNION ALL SELECT 'window',hex(json_object('operation',op,'startNs',lo,'endNs',hi)) FROM windows
    """
    result = subprocess.run([sys.executable, str(args.processor), 'query', str(stem) + '.perfetto', sql],
        capture_output=True, text=True, check=True)
    records = {}
    for row in csv.DictReader(io.StringIO(result.stdout)):
        records.setdefault(row['kind'], []).append(json.loads(bytes.fromhex(row['payload']).decode()))
    output = args.output or Path(str(stem) + '-cpu.json')
    if output.exists():
        raise RuntimeError('Preserve prior CPU evidence')
    output.write_text(json.dumps({'sql': sql, 'records': records,
        'clockDomain': 'Perfetto only', 'definition': 'Overlap with exact accepted-to-present windows; concurrent runtime CPU is contextual, not proof of causal blocking; nested runtime slices overlap.'}, indent=2))
    print(json.dumps({'arm': source['buildLabel'], 'windows': len(records.get('window', []))}))

if __name__ == '__main__':
    main()
