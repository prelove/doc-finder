#!/usr/bin/env python3
"""Materialize 200 fixed benchmark files from the committed indexing corpus."""
import argparse
import hashlib
import os
from pathlib import Path
import shutil

parser = argparse.ArgumentParser()
parser.add_argument('destination', type=Path)
args = parser.parse_args()
source = Path(__file__).resolve().parents[1] / 'src/test/resources/index/virtual-thread-corpus/docs'
args.destination.mkdir(parents=True, exist_ok=False)
entries = []
for repetition in range(20):
    for original in sorted(source.rglob('*')):
        if not original.is_file():
            continue
        target = args.destination / ('batch-%02d' % repetition) / original.relative_to(source)
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(original, target)
        os.utime(target, (1700000000, 1700000000))
        entries.append('%s\t%s' % (target.relative_to(args.destination), hashlib.sha256(target.read_bytes()).hexdigest()))
# Manifest remains outside the indexed root, so it cannot affect corpus counts.
args.destination.with_suffix('.manifest.tsv').write_text('\n'.join(entries) + '\n')
print('files=%d sha256_manifest=%s' % (len(entries), hashlib.sha256(('\n'.join(entries) + '\n').encode()).hexdigest()))
