"""Score fixed, manually transcribed owner-photo regions; not a full-page OCR benchmark."""
import argparse
import json
from pathlib import Path


def distance(a, b):
    row = list(range(len(b) + 1))
    for i, x in enumerate(a, 1):
        next_row = [i]
        for j, y in enumerate(b, 1):
            next_row.append(min(next_row[-1] + 1, row[j] + 1, row[j-1] + (x != y)))
        row = next_row
    return row[-1]


def score(truth, layout):
    details = []
    for region in truth['regions']:
        candidates = [l for l in layout['lines'] if min(p[0] for p in l['corners']) < region['leftMax']
                      and abs(sum(p[1] for p in l['corners'])/4 - region['cy']) < .012]
        line = min(candidates, key=lambda l: abs(sum(p[1] for p in l['corners'])/4-region['cy']), default=None)
        observed = line['text'] if line else ''
        expected = ''.join(region['text'].split())
        actual = ''.join(observed.split())
        details.append(dict(expected=region['text'], observed=observed,
                            edits=distance(expected, actual), characters=len(expected), cy=region['cy']))
    return dict(edits=sum(r['edits'] for r in details), characters=sum(r['characters'] for r in details),
                exactRegions=sum(r['edits'] == 0 for r in details), regions=len(details), details=details)


def main():
    p = argparse.ArgumentParser()
    p.add_argument('--truth', type=Path, required=True)
    p.add_argument('--folder', type=Path, required=True)
    p.add_argument('--out', type=Path, required=True)
    args = p.parse_args()
    truth = json.loads(args.truth.read_text(encoding='utf-8'))
    result = {'partialManuallyTranscribedRegions': True, 'fullPageCER': False, 'rows': {}}
    for path in sorted(args.folder.glob('ocr-*.json')):
        layout = json.loads(path.read_text(encoding='utf-8'))
        if 'lines' not in layout:
            continue
        row = score(truth, layout)
        row['regionCharacterErrorRate'] = row['edits'] / max(1, row['characters'])
        result['rows'][path.stem] = row
    args.out.write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding='utf-8')
    for name, row in result['rows'].items():
        print(name, row['edits'], '/', row['characters'], 'exact', row['exactRegions'], '/', row['regions'])


if __name__ == '__main__':
    main()
