#!/usr/bin/env bash
set -euo pipefail
repo_root=$(cd "$(dirname "$0")/../.." && pwd)
jdk8=${JDK8_HOME:-/home/hatch/tools/jdk8}
lucene8_jar=${LUCENE8_JAR:-/home/hatch/m2-repository/org/apache/lucene/lucene-core/8.11.2/lucene-core-8.11.2.jar}
fixture_work=$(mktemp -d)
trap 'rm -rf "$fixture_work"' EXIT
mkdir -p "$fixture_work/classes"
"$jdk8/bin/javac" -cp "$lucene8_jar" -d "$fixture_work/classes" "$repo_root/tools/fixtures/Lucene8FixtureGenerator.java"
"$jdk8/bin/java" -cp "$fixture_work/classes:$lucene8_jar" Lucene8FixtureGenerator "$fixture_work/data"
python3 - "$fixture_work/data" "$repo_root/src/test/resources/index/lucene8.zip" <<'PY'
import sys
from pathlib import Path
from zipfile import ZipFile, ZIP_DEFLATED
base = Path(sys.argv[1])
with ZipFile(sys.argv[2], 'w', ZIP_DEFLATED) as archive:
    for path in sorted(base.rglob('*')):
        if path.is_file():
            archive.write(path, path.relative_to(base))
PY
