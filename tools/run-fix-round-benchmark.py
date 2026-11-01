#!/usr/bin/env python3
"""Run three independent JVMs per scheduling mode, recording the final run in JFR.

First run mvn -o test, then generate-virtual-thread-benchmark.py CORPUS.
The Surefire report supplies exactly the tested dependency classpath.
"""
import argparse
from pathlib import Path
import statistics
import subprocess
import xml.etree.ElementTree as ET

parser = argparse.ArgumentParser()
parser.add_argument('corpus', type=Path)
parser.add_argument('output', type=Path)
parser.add_argument('--java-home', type=Path, default=Path('/home/hatch/tools/jdk21'))
args = parser.parse_args()
args.corpus = args.corpus.resolve()
args.output = args.output.resolve()
args.output.mkdir(parents=True, exist_ok=False)
repo = Path(__file__).resolve().parents[1]
report = repo / 'target/surefire-reports/TEST-org.abitware.docfinder.index.IndexFormatGuardTest.xml'
classpath = ET.parse(report).find(".//property[@name='java.class.path']").get('value')
java = str(args.java_home / 'bin/java')
jfr = str(args.java_home / 'bin/jfr')
settings = args.output / 'pinning.jfc'
subprocess.run([jfr, 'configure', '--input', 'profile.jfc',
                'jdk.VirtualThreadPinned#enabled=true', 'jdk.VirtualThreadPinned#threshold=0ms',
                '--output', str(settings)], check=True)
for mode in ['SERIAL', 'PARALLEL']:
    times = []
    for repetition in range(1, 4):
        run = args.output / ('%s-%d' % (mode.lower(), repetition))
        run.mkdir()
        command = [java, '-Djava.net.preferIPv4Stack=true', '-Duser.home=' + str(run), '-Duser.dir=' + str(run)]
        if mode == 'PARALLEL' and repetition == 3:
            command.append('-XX:StartFlightRecording=filename=%s,settings=%s,dumponexit=true' %
                           (args.output / 'parallel.jfr', settings))
        command += ['-cp', classpath, 'org.abitware.docfinder.index.IndexingFixRoundBenchmark',
                    str(args.corpus), str(run / 'index'), mode]
        logfile = run / 'run.log'
        with logfile.open('w') as output:
            result = subprocess.run(command, cwd=run, stdout=output, stderr=subprocess.STDOUT)
        summaries = [line for line in logfile.read_text().splitlines() if line.startswith('BENCH ')]
        print(mode, repetition, 'exit', result.returncode, *summaries, flush=True)
        if result.returncode or len(summaries) != 1:
            raise SystemExit('Benchmark failed; inspect ' + str(logfile))
        values = dict(item.split('=', 1) for item in summaries[0].split() if '=' in item)
        times.append(int(values['elapsed_ms']))
    print('%s median_ms=%s' % (mode, statistics.median(times)), flush=True)
with (args.output / 'jfr-summary.txt').open('w') as output:
    subprocess.run([jfr, 'summary', str(args.output / 'parallel.jfr')], stdout=output, check=True)
with (args.output / 'pinned-events.txt').open('w') as output:
    subprocess.run([jfr, 'print', '--events', 'jdk.VirtualThreadPinned',
                    str(args.output / 'parallel.jfr')], stdout=output, check=True)
