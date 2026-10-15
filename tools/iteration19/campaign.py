"""One candidate; fixed counterbalanced campaign with immutable per-arm logs."""
import json
import subprocess
import sys
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / 'scratch/iteration19/runs'
OUT.mkdir(exist_ok=True)
(OUT/'logs').mkdir(exist_ok=True)
cells = [('90g', '90', 'grouped'), ('1825g', '1825', 'grouped'), ('90s', '90', 'entrySensitive')]
mode = sys.argv[1] if len(sys.argv) > 1 else 'presentation'
arms = []
if mode == 'coarse':
    arms = [(a, f'iter19-coarse2-{a[0]}', '90', 'grouped', 'false', 'true', 2)
            for a in ('baseline', 'candidate')]
else:
    for cell, days, order in cells:
        for arm, num, reverse in [('baseline',1,'false'), ('candidate',1,'false'),
                ('candidate',2,'true'), ('baseline',2,'true'),
                ('baseline',3,'false'), ('candidate',3,'false')]:
            arms.append((arm, f'iter19-{cell}-{arm[0]}{num}', days, order, reverse, 'false', 4))

for arm, label, days, order, reverse, coarse, samples in arms:
    log = OUT / 'logs' / (label + '.runner.log')
    if log.exists():
        raise RuntimeError(f'Preserve previous attempt: {log}')
    command = [sys.executable, str(ROOT/'scratch/iteration19/run_arm.py'),
        '--arm-type',arm,'--label',label,'--history-days',days,'--sort',order,
        '--reverse',reverse,'--samples',str(samples),'--resort-diagnostics-enabled','false',
        '--coarse-resort-spans-enabled',coarse]
    print('START', label, flush=True)
    with log.open('w', encoding='utf-8') as stream:
        result = subprocess.run(command, cwd=ROOT, stdout=stream, stderr=subprocess.STDOUT)
    with (OUT/'campaign-events.jsonl').open('a', encoding='utf-8') as stream:
        stream.write(json.dumps(dict(label=label, returncode=result.returncode, timestamp=time.time()))+'\n')
    print('END', label, result.returncode, flush=True)
    if result.returncode:
        raise SystemExit(result.returncode)
