"""Bounded coarse attribution. One local trace load; no server and no acceptance decision."""
import argparse
import csv
import io
import json
from pathlib import Path
import subprocess
import sys

SCHEMA = ['slice', 'thread_track', 'thread', 'thread_state', 'stats']
COARSE_NAMES = ['memory.sort', 'memory.notify', 'sphere.rootLookup', 'sphere.rootIndices',
                'sqlite.blocks', 'sqlite.facadeAssignment', 'sqlite.notify']
SQL = "\n".join(f'SELECT * FROM {name} LIMIT 0;' for name in SCHEMA) + r'''
-- Schemas were discovered on iter18-pilot-b with skill wrapper v58.2.
-- Nested coarse intervals overlap: do not SPAN_JOIN them without separate partitions.
-- Export bounded same-thread state once; Python clips each inclusive slice separately.
CREATE OR REPLACE PERFETTO TABLE coarse_windows AS
SELECT s.id,s.ts,s.dur,s.name,s.depth,s.parent_id,tt.utid,t.tid,t.name AS thread_name,t.upid
FROM slice s JOIN thread_track tt ON tt.id=s.track_id JOIN thread t ON t.utid=tt.utid
WHERE s.name GLOB 'habit.resortStage.[0-9]*' OR s.name GLOB 'habit.resortCoarse.*';
SELECT 'slice' AS kind,hex(json_object('id',c.id,'ts',c.ts,'dur',c.dur,'name',c.name,
 'depth',c.depth,'parent_id',c.parent_id,'utid',c.utid,'tid',c.tid,'thread',c.thread_name,'upid',c.upid)) AS payload
FROM coarse_windows c
UNION ALL
SELECT 'state',hex(json_object('id',s.id,'ts',s.ts,'dur',s.dur,'utid',s.utid,'state',s.state,
 'blocked_function',s.blocked_function,'io_wait',s.io_wait))
FROM thread_state s JOIN
 (SELECT c.utid,min(c.ts) lo,max(c.ts+CASE WHEN c.dur<0 THEN 0 ELSE c.dur END) hi
  FROM coarse_windows c GROUP BY c.utid) w ON w.utid=s.utid
WHERE s.ts<w.hi AND (s.dur<0 OR s.ts+s.dur>w.lo)
UNION ALL
SELECT 'stat',hex(json_object('name',s.name,'idx',s.idx,'severity',s.severity,'source',s.source,
 'value',s.value,'description',s.description)) FROM stats s WHERE s.value!=0;
'''


def attribution(interval, states):
    """Inclusive clipped state coverage; unrecorded coverage remains unknown."""
    lo, hi = interval['ts'], interval['ts'] + interval['dur']
    totals = {'running': 0, 'runnable': 0, 'sleepS': 0, 'uninterruptibleD': 0, 'other': 0}
    blocked = {}
    overlap = 0
    last_end = lo
    for s in states:
        if s['ts'] >= hi:
            break
        if s['dur'] < 0:
            continue
        a, b = max(lo, s['ts']), min(hi, s['ts'] + s['dur'])
        if b <= a:
            continue
        overlap += max(0, min(last_end, b) - a)
        last_end = max(last_end, b)
        key = ('running' if s['state'] == 'Running' else 'runnable' if s['state'] in ('R', 'R+')
               else 'sleepS' if s['state'] == 'S' else 'uninterruptibleD' if s['state'].startswith('D') else 'other')
        totals[key] += b-a
        if s['blocked_function']:
            blocked[s['blocked_function']] = blocked.get(s['blocked_function'], 0) + b-a
    return dict(**{k+'Ms': v/1e6 for k, v in totals.items()},
                unknownMs=max(0, interval['dur']-sum(totals.values()))/1e6,
                overlappingStateMs=overlap/1e6,
                blockedFunctionMs={k: v/1e6 for k, v in blocked.items()})


def analyze(rows):
    slices = rows.get('slice', [])
    stages = [s for s in slices if s['name'].startswith('habit.resortStage.')]
    coarse = [s for s in slices if s['name'].startswith('habit.resortCoarse.')]
    states = {}
    for s in rows.get('state', []):
        states.setdefault(s['utid'], []).append(s)
    for values in states.values():
        values.sort(key=lambda s: s['ts'])
    operations = []
    assigned = set()
    for stage in sorted(stages, key=lambda s: s['ts']):
        operation = stage['name'].removeprefix('habit.resortStage.')
        item = {'operation': operation, 'slice': stage, 'status': 'complete' if stage['dur'] >= 0 else 'incomplete'}
        if stage['dur'] < 0:
            operations.append(item)
            continue
        contained = [s for s in coarse if s['utid'] == stage['utid'] and s['dur'] >= 0
                     and stage['ts'] <= s['ts'] and s['ts']+s['dur'] <= stage['ts']+stage['dur']]
        item['stageInclusive'] = {'wallMs': stage['dur']/1e6, **attribution(stage, states.get(stage['utid'], []))}
        item['coarseStages'] = {}
        for s in contained:
            assigned.add(s['id'])
            name = s['name'].removeprefix('habit.resortCoarse.')
            measurement = {'sliceId': s['id'], 'wallMs': s['dur']/1e6,
                           **attribution(s, states.get(s['utid'], []))}
            item['coarseStages'].setdefault(name, []).append(measurement)
        item['counts'] = {k: len(item['coarseStages'].get(k, [])) for k in COARSE_NAMES}
        item['inclusiveTotals'] = {k: {metric: sum(s[metric] for s in values) for metric in
            ['wallMs', 'runningMs', 'runnableMs', 'sleepSMs', 'uninterruptibleDMs', 'otherMs', 'unknownMs']}
            for k, values in item['coarseStages'].items()}
        notifies = [s for s in contained if s['name'] == 'habit.resortCoarse.memory.notify']
        outer = [s for s in notifies if not any(p['id'] != s['id'] and p['depth'] < s['depth']
                 and p['ts'] <= s['ts'] and s['ts']+s['dur'] <= p['ts']+p['dur'] for p in notifies)]
        item['outermostMemoryNotifyInclusive'] = {
            'count': len(outer), 'sliceIds': [s['id'] for s in outer],
            'wallMs': sum(s['dur'] for s in outer)/1e6,
            'runningMs': sum(attribution(s, states.get(s['utid'], []))['runningMs'] for s in outer)}
        operations.append(item)
    ordinal_counts = {}
    for op in operations:
        ordinal_counts[op['operation']] = ordinal_counts.get(op['operation'], 0)+1
    return {'operations': operations,
            'duplicateOperationOrdinals': {k: v for k, v in ordinal_counts.items() if v != 1},
            'unassignedOrIncompleteCoarseSlices': [s for s in coarse if s['id'] not in assigned],
            'traceStatsNonzero': rows.get('stat', []),
            'outOfOrderStats': [s for s in rows.get('stat', []) if 'out_of_order' in s['name']],
            'definitions': [
                'All stage measurements are inclusive and may overlap. Do not sum coarse stages.',
                'sphere.rootLookup includes its SQLite work; this is not pure SQL cost.',
                'Observer total uses only outermost memory.notify slices. Nested callbacks remain separately visible.',
                'runningMs is scheduled CPU on the same thread; runnable is scheduling delay.',
                'S/D states do not prove lock waiting. blocked_function is contextual evidence only.',
                'Missing/incomplete scheduling stays unknown; no wall-minus-CPU lock attribution.',
                'No acceptance decision is made by this artifact.']}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--trace', type=Path, required=True)
    parser.add_argument('--processor', type=Path, required=True, help='Perfetto skill bin/trace_processor wrapper')
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    outputs = [args.output, args.output.with_suffix('.sql'), args.output.with_suffix('.csv'),
               args.output.with_suffix('.stderr.txt'), args.output.with_suffix('.md')]
    if any(p.exists() for p in outputs):
        parser.error('Preserve prior evidence: choose an unused output stem')
    args.output.parent.mkdir(parents=True, exist_ok=True)
    outputs[1].write_text(SQL, encoding='utf-8')
    # SQL is passed through stdin; saved SQL is an evidence artifact, not an executed SQL file.
    result = subprocess.run([sys.executable, str(args.processor), 'query', str(args.trace)],
                            input=SQL, capture_output=True, text=True, encoding='utf-8', errors='replace')
    outputs[2].write_text(result.stdout, encoding='utf-8')
    outputs[3].write_text(result.stderr, encoding='utf-8')
    result.check_returncode()
    blocks = result.stdout.strip().split('\n\n')
    discovered = blocks[:len(SCHEMA)]
    if len(discovered) != len(SCHEMA) or '"kind","payload"' not in blocks[-1]:
        raise RuntimeError('Unexpected schema/result framing; raw evidence retained')
    rows = {}
    for row in csv.DictReader(io.StringIO(blocks[-1])):
        rows.setdefault(row['kind'], []).append(json.loads(bytes.fromhex(row['payload']).decode()))
    report = analyze(rows)
    report.update(trace=str(args.trace.resolve()), schemaHeaders=dict(zip(SCHEMA, discovered)),
                  command=[sys.executable, str(args.processor), 'query', str(args.trace)],
                  traceProcessorStderr=str(outputs[3].resolve()))
    args.output.write_text(json.dumps(report, indent=2)+'\n', encoding='utf-8')
    outputs[4].write_text('# Coarse resort attribution\n\nTrace: '+str(args.trace.resolve())+
        '\n\nOperations: '+str(len(report['operations']))+
        '\n\nSee adjacent JSON for exact per-operation inclusive wall, running CPU, runnable, S/D and unknown coverage; '
        'SQL and raw CSV/stderr are retained. Inspect nonzero trace stats, especially out-of-order events, '
        'before interpreting results. Root lookup includes SQLite. Coarse spans overlap; observer total includes '
        'only outermost memory.notify. No acceptance decision.\n', encoding='utf-8')
    print(json.dumps({'output': str(args.output), 'operations': len(report['operations']),
                      'unassignedCoarse': len(report['unassignedOrIncompleteCoarseSlices'])}))


if __name__ == '__main__':
    main()
