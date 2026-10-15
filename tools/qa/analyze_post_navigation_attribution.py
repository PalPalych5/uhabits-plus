"""Strict post-navigation attribution; monotonic domains remain separate."""
import argparse
import copy
import importlib.util
import json
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location('remaining', ROOT / 'analyze_remaining_latency.py')
remaining = importlib.util.module_from_spec(spec)
spec.loader.exec_module(remaining)

def qualify_operation_scope(source, presentation):
    """Preserve physical joins while excluding later scenario draws from edit latency."""
    result = copy.deepcopy(presentation)
    native = {o['operation']: o for o in source['operations'] if 'operation' in o}
    for op in result['operations']:
        if op['status'] != 'linked':
            continue
        original = native.get(op['ordinal'], {})
        draws = [e for e in source['events'] if e.get('operation') == op['ordinal']
                 and e['stage'] == 'glyph.draw']
        valid = (original.get('scenario') is not None and len(draws) == 1
                 and draws[0].get('phase') == original['scenario']
                 and ('finishedNanos' not in original
                      or draws[0]['timestampNanos'] <= original['finishedNanos']))
        if not valid:
            op['outOfScopePresentedCandidate'] = copy.deepcopy(op)
            op['status'] = 'unmatched'
            op['reason'] = 'Committed glyph is missing, ambiguous, or outside original operation scope'
            for key in ('inputToCommittedPresentationMs', 'committedDrawToPresentationMs'):
                op.pop(key, None)
        else:
            op['operationScope'] = ('finishedBoundary' if 'finishedNanos' in original
                                    else 'legacyScenarioBoundary')
    result['coverage'] = {s: sum(o['status'] == s for o in result['operations'])
                          for s in ('linked', 'unmatched', 'ambiguous', 'notApplicable')}
    values = sorted(o['inputToCommittedPresentationMs'] for o in result['operations'] if o['status'] == 'linked')
    result['inputToCommittedPresentation'] = remaining.distribution(values)
    return result


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--source', type=Path, required=True)
    parser.add_argument('--processor', type=Path, required=True)
    parser.add_argument('--analysis-name', default='analysis')
    parser.add_argument('--skip-context-trace', action='store_true',
        help='Use companion exact-window CPU/layout reports instead of expensive whole-capture stdlib context')
    args = parser.parse_args()
    stem = args.source.with_suffix('')
    source = json.loads(args.source.read_text(encoding='utf-8-sig'))
    def save(suffix, value):
        path = Path(str(stem) + suffix)
        if path.exists():
            if json.loads(path.read_text(encoding='utf-8')) == value:
                return path
            raise RuntimeError('Preserve previous analysis: ' + str(path))
        path.write_text(json.dumps(value, indent=2) + '\n', encoding='utf-8')
        return path
    correlation = save('-correlation.json', remaining.correlate_events(source))
    presentations = []
    for label, prefix in [('down', 'habit.op.input.'), ('accepted', 'habit.op.accepted.')]:
        output = Path(str(stem) + '-' + label + '-presentation.json')
        if not output.exists():
            subprocess.run([sys.executable, str(ROOT / 'analyze-operation-trace.py'),
                '--trace', str(stem) + '.perfetto', '--processor', str(args.processor),
                '--events', str(correlation), '--input-prefix', prefix, '--output', str(output)], check=True)
        qualified = qualify_operation_scope(source, json.loads(output.read_text(encoding='utf-8')))
        save('-' + label + '-scoped-presentation.json', qualified)
        presentations.append(qualified)
    output = remaining.summarize(source, presentations[0])
    output['acceptedPresentation'] = presentations[1]
    if 'navigationOperations' in source:
        navigation = save('-navigation-events.json', {
            'pid': source['pid'], 'operations': [
                dict(n, ordinal=n['navigationOperation'], events=[])
                for n in source['navigationOperations'] if n['destination'] == 'HABITS'
            ]})
        path = Path(str(stem) + '-navigation-presentation.json')
        if not path.exists():
            subprocess.run([sys.executable, str(ROOT / 'analyze-operation-trace.py'),
                '--trace', str(stem) + '.perfetto', '--processor', str(args.processor),
                '--events', str(navigation), '--input-prefix', 'habit.nav.input.',
                '--draw-prefix', 'habit.nav.habitsDraw.', '--output', str(path)], check=True)
        output['navigationPresentation'] = json.loads(path.read_text(encoding='utf-8'))
    if args.skip_context_trace:
        output['traceAttribution'] = {'records': {}, 'scope': 'Not collected by this invocation; see companion exact-window CPU/layout reports'}
    else:
        output['traceAttribution'] = remaining.trace_attribution(Path(str(stem) + '.perfetto'), args.processor, source['pid'])
        remaining.attach_trace_operations(output['operations'], output['traceAttribution'])
    for op in output['operations']:
        if 'operation' not in op:
            continue
        events = [e for e in source['events'] if e.get('operation') == op['operation']]
        by_stage = {}
        for e in events:
            by_stage.setdefault(e['stage'], []).append(e)
        def delta(first, last):
            a, b = by_stage.get(first, []), by_stage.get(last, [])
            if len(a) == len(b) == 1:
                return (b[0]['timestampNanos'] - a[0]['timestampNanos']) / 1e6
        op['setupToAcceptedMs'] = delta('harness.setup.start', 'input.accepted')
        op['injectionHarnessToAcceptedMs'] = delta('harness.input.injection.start', 'input.accepted')
        op['systemErrorCheckMs'] = next((e['durationNanos'] / 1e6 for e in events
            if e['stage'] == 'harness.systemCheck.end'), None)
        ordering = ['input.down', 'input.accepted', 'command.accepted', 'command.enqueued',
                    'command.worker', 'command.fifoStart', 'sqlite.begin', 'sqlite.end', 'sqlite.durable']
        observed = [(stage, by_stage[stage][0]['timestampNanos']) for stage in ordering
                    if len(by_stage.get(stage, [])) == 1]
        op['ordering'] = {'observed': observed,
            'monotonic': all(a[1] <= b[1] for a, b in zip(observed, observed[1:])),
            'missingOrRepeated': [s for s in ordering if len(by_stage.get(s, [])) != 1]}
        draw = by_stage.get('glyph.draw', [])
        if len(draw) == 1:
            lineage = tuple(draw[0].get(k) for k in ('revision', 'generation', 'batch'))
            pubs = [e for e in by_stage.get('cache.row.published', [])
                    if tuple(e.get(k) for k in ('revision', 'generation', 'batch')) == lineage]
            durable = op['stageMetrics']['actualDurability']
            if len(pubs) == 1 and durable['status'] == 'qualifiedAutocommitStepReturn':
                op['actualDurableToQualifiedRowMs'] = (pubs[0]['timestampNanos'] - durable['timestampNanos']) / 1e6
                op['qualifiedRowToGlyphMs'] = (draw[0]['timestampNanos'] - pubs[0]['timestampNanos']) / 1e6
                binds = [e for e in by_stage.get('glyph.bind', [])
                    if tuple(e.get(k) for k in ('revision', 'generation', 'batch')) == lineage
                    and pubs[0]['timestampNanos'] <= e['timestampNanos'] <= draw[0]['timestampNanos']]
                op['qualifiedBind'] = {'status': 'linked' if len(binds) == 1 else 'unmatched',
                    'candidateCount': len(binds)}
                if len(binds) == 1:
                    op['qualifiedBind']['publicationToBindMs'] = (binds[0]['timestampNanos'] - pubs[0]['timestampNanos']) / 1e6
                    op['qualifiedBind']['bindToDrawMs'] = (draw[0]['timestampNanos'] - binds[0]['timestampNanos']) / 1e6
                recompute = by_stage.get('repetition.modelRecompute.end', [])
                sequence = [('sqlite.return', durable['timestampNanos'])]
                if len(recompute) == 1:
                    sequence.append(('model.recomputed', recompute[0]['timestampNanos']))
                sequence.extend([('qualified.row', pubs[0]['timestampNanos']), ('glyph.draw', draw[0]['timestampNanos'])])
                op['qualifiedPublicationOrdering'] = {'observed': sequence,
                    'monotonic': all(a[1] <= b[1] for a, b in zip(sequence, sequence[1:]))}
                target_batch = draw[0].get('batch')
                def unique(stage):
                    matches = [e for e in events if e['stage'] == stage and e.get('batch') == target_batch
                        and e['timestampNanos'] <= draw[0]['timestampNanos']]
                    return matches[0]['timestampNanos'] if len(matches) == 1 else None
                points = {'request': unique('cache.request'), 'worker': unique('cache.background.begin'),
                    'ready': unique('cache.background.end'), 'snapshotBegin': unique('snapshot.begin'),
                    'snapshotEnd': unique('snapshot.end'), 'publication': pubs[0]['timestampNanos']}
                op['qualifiedCachePath'] = {'batch': target_batch, 'points': points}
                for name, first, last in [('requestToWorkerMs', 'request', 'worker'),
                    ('workerToReadyMs', 'worker', 'ready'), ('readyToPublicationMs', 'ready', 'publication'),
                    ('snapshotMs', 'snapshotBegin', 'snapshotEnd')]:
                    if points[first] is not None and points[last] is not None and points[last] >= points[first]:
                        op['qualifiedCachePath'][name] = (points[last] - points[first]) / 1e6
    output['fixtureLogicalSha256'] = source.get('fixtureLogicalSha256')
    output['clockBeginning'] = source.get('clockBeginning')
    output['clockEnd'] = source.get('clockEnd')
    save('-' + args.analysis_name + '.json', output)
    print(json.dumps({'arm': source['buildLabel'], 'down': presentations[0]['coverage'],
        'accepted': presentations[1]['coverage'], 'fixture': source.get('fixtureLogicalSha256')}))

if __name__ == '__main__':
    main()
