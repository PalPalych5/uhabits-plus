"""Validate fresh arms and retain run/scenario/tail/endpoint variation."""
import json
import math
import statistics
from collections import defaultdict
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2] / 'scratch/iteration19'

def dist(values):
    values = sorted(values)
    if not values:
        return {'n': 0}
    return dict(n=len(values), median=statistics.median(values),
                p95=values[math.ceil(.95*len(values))-1], maximum=values[-1],
                mean=statistics.mean(values))

runs = []
pooled = defaultdict(lambda: defaultdict(list))
scenarios = defaultdict(lambda: defaultdict(lambda: defaultdict(list)))
fingerprints = defaultdict(set)
for path in sorted((ROOT/'runs').glob('iter19-*-final.json')):
    label = path.name.removesuffix('-final.json')
    if 'coarse' in label:
        continue
    _, cell, arm_id = label.split('-')
    arm = 'baseline' if arm_id.startswith('b') else 'candidate'
    source = json.loads((path.parent/(label+'.json')).read_text(encoding='utf-8-sig'))
    final = json.loads(path.read_text())
    host = json.loads((path.parent/(label+'.host-env.json')).read_text())
    operations = [o for o in source['operations'] if 'operation' in o]
    assert source['schemaVersion'] == 30 and source['journalMode'].lower() == 'wal'
    assert not source['resortDiagnosticsEnabled'] and source['rangeProbeCycles'] == 0
    assert source['controlledReaderFailureCount'] == 0
    assert all(o['sqliteSnapshotParity'] and o['viewParity'] and o['viewAvailable'] for o in operations)
    fingerprints[cell].add(source['fixtureLogicalSha256'])
    by_op = {o['operation']: o for o in operations}
    metrics = defaultdict(list)
    for o in final['acceptedPresentation']['operations']:
        if o['status'] == 'linked':
            value = o['inputToCommittedPresentationMs']
            metrics['presentation'].append(value)
            scenarios[(cell,arm)][o['scenario']]['presentation'].append(value)
            metrics['firstPresentation' if o['ordinal'] == 1 else 'subsequentPresentation'].append(value)
    for e in source['events']:
        if e['stage'] == 'resort.stage.cpu':
            for metric, key in [('resortWall','wallNanos'), ('resortCpu','cpuNanos')]:
                value = e[key]/1e6
                metrics[metric].append(value)
                scenarios[(cell,arm)][by_op[e['operation']]['scenario']][metric].append(value)
    for o in final['navigationPresentation']['operations']:
        if o['status'] == 'linked':
            metrics['navigation'].append(o['inputToCommittedPresentationMs'])
    for metric, values in metrics.items():
        pooled[(cell,arm)][metric].extend(values)
    runs.append(dict(label=label, cell=cell, arm=arm, metrics={k:dist(v) for k,v in metrics.items()},
        acceptedCoverage=final['acceptedPresentation']['coverage'],
        navigationCoverage=final['navigationPresentation']['coverage'],
        unmatched=[o for o in final['acceptedPresentation']['operations'] if o['status']!='linked'],
        fingerprint=source['fixtureLogicalSha256'], parityEdits=len(operations),
        host={k:v for k,v in host.items() if k!='samples'}))
assert all(len(v)==1 for v in fingerprints.values()), 'Logical fixtures differ'
result = dict(runs=runs, pooled={f'{c}/{a}':{k:dist(v) for k,v in m.items()} for (c,a),m in pooled.items()},
    scenarios={f'{c}/{a}':{s:{k:dist(v) for k,v in m.items()} for s,m in ss.items()} for (c,a),ss in scenarios.items()},
    fingerprints={k:list(v) for k,v in fingerprints.items()}, p95Definition='nearest rank, ceil(.95*n)-1')
(ROOT/'comparison.json').write_text(json.dumps(result,indent=2))
for run in runs:
    print(run['label'], {k:round(v['median'],2) for k,v in run['metrics'].items() if 'median' in v}, run['acceptedCoverage'])
print('POOLED', json.dumps(result['pooled'],indent=2))
