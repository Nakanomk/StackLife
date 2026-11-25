#!/usr/bin/env python3
# SQL adapted from Perfetto v59.1, Copyright 2024 The Android Open Source Project.
# SPDX-License-Identifier: Apache-2.0
# https://github.com/google/perfetto/tree/1e9c61e9844affd7dccd3d42d9ea9f3a3d10cf6d
# src/trace_processor/perfetto_sql/stdlib/android/frames/timeline.sql (android_frames_layers):
# match expected/actual by process and frame ID. Use FrameTimeline's app token directly,
# as in docs/data-sources/frametimeline.md, without requiring HWUI or SF slices.
# src/trace_processor/importers/proto/frame_timeline_event_parser.cc: jank/present labels.
# src/trace_processor/shell/query_subcommand.cc: explicit CSV independent of terminal formatting.
# StackLife adds its bench window, five-run grouping, nearest rank and release thresholds.
"""Score five already-warmed traces of ONE scenario per material using local Perfetto v59.1.

Qualify the material, warm-up, scene boundaries and quiet device at collection time:
this reads every paired target window frame in each trace, not filenames or device state.
Exit 0: both groups passed; 1: budget failed; 2: invalid input or processor error.
"""

import argparse
import csv
import hashlib
import io
import json
from pathlib import Path
import subprocess


PACKAGE = "io.github.nakanomk.stacklife.bench"
COMPONENT = PACKAGE + "/io.github.nakanomk.stacklife.MainActivity"
QUERY = f"""
SELECT a.ts + a.dur - (e.ts + e.dur) AS overrun_ns,
       CASE WHEN a.jank_type GLOB '*App Deadline Missed*'
              OR a.jank_type GLOB '*Dropped Frame*' OR a.present_type = 'Dropped Frame'
            THEN 1 ELSE 0 END AS missed,
       count(*) OVER (PARTITION BY a.upid, a.surface_frame_token) AS pair_count
FROM actual_frame_timeline_slice AS a
JOIN expected_frame_timeline_slice AS e USING (upid, surface_frame_token)
JOIN process AS p ON p.upid = a.upid
WHERE p.name = '{PACKAGE}' AND a.surface_frame_token > 0 AND a.dur > 0 AND e.dur > 0
  AND (a.layer_name GLOB 'TX - {COMPONENT}#*' OR a.layer_name GLOB '{COMPONENT}#*')
"""


def trace_digest(path):
    if not path.is_file():
        raise ValueError(f"not a trace file: {path}")
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.digest()


def read_frames(processor, path):
    result = subprocess.run(
        [processor, "query", "--quiet", "--format", "csv", str(path.absolute()), QUERY],
        capture_output=True, text=True,
    )
    if result.returncode:
        raise ValueError(f"{path}: processor exited {result.returncode}: {result.stderr.strip()}")
    rows = csv.reader(io.StringIO(result.stdout), strict=True)
    if next(rows, None) != ["overrun_ns", "missed", "pair_count"]:
        raise ValueError(f"{path}: expected a Perfetto CSV result with the frame columns")
    overruns = []
    misses = 0
    for row in rows:
        if len(row) != 3 or row[1] not in ("0", "1"):
            raise ValueError(f"{path}: invalid frame row at CSV line {rows.line_num}")
        if row[2] != "1":
            raise ValueError(f"{path}: ambiguous window frame/token pairing at CSV line {rows.line_num}")
        overruns.append(int(row[0]))
        misses += int(row[1])
    if not overruns:
        raise ValueError(f"{path}: no complete, paired {COMPONENT} frames")
    return overruns, misses


def score(processor, paths):
    overruns = []
    misses = 0
    iterations = []
    for path in paths:
        ns, missed = read_frames(processor, path)
        iterations.append(dict(trace=str(path), windowFrames=len(ns), misses=missed))
        overruns.extend(ns)
        misses += missed
    overruns.sort()
    count = len(overruns)
    p95_ns = overruns[(95 * count - 1) // 100]  # ceil(0.95 * count) - 1
    return dict(iterations=iterations, windowFrames=count, p95Ms=p95_ns / 1_000_000,
                appMissedOrDropped=misses, appMissedOrDroppedPct=100 * misses / count,
                passed=p95_ns <= 0 and 100 * misses < count)


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--trace-processor", default="trace_processor_shell", help="installed offline shell binary")
    for mode in ("glass", "solid"):
        parser.add_argument(f"--{mode}", type=Path, nargs=5, action="extend", metavar="TRACE", required=True)
    args = parser.parse_args()
    modes = {"glass": args.glass, "solid": args.solid}
    try:
        seen = {}
        for mode, paths in modes.items():
            if len(paths) != 5:
                raise ValueError(f"--{mode} requires exactly five traces")
            for path in paths:
                digest = trace_digest(path)
                if digest in seen:
                    raise ValueError(f"duplicate trace input: {path} has identical contents to {seen[digest]}")
                seen[digest] = path
        report = {mode: score(args.trace_processor, paths) for mode, paths in modes.items()}
    except (OSError, ValueError, csv.Error) as error:
        parser.exit(2, f"{parser.prog}: {error}\n")
    print(json.dumps(report, indent=2))
    return 0 if all(group["passed"] for group in report.values()) else 1


if __name__ == "__main__":
    raise SystemExit(main())
