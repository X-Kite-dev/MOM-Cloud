#!/usr/bin/env python3
"""Compile and execute real service tests using an existing offline Maven classpath.

Usage: python3 src/test/run-statistics-regression.py --classpath-file /tmp/mes-stats-testclasspath.txt
The explicit runner is needed because the existing Surefire 2.12.4 skips Jupiter tests.
No dependency resolution, database, or server is started.
"""
import argparse
from pathlib import Path
import subprocess
import tempfile
import sys

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("--classpath-file", required=True, type=Path)
args = parser.parse_args()
biz = Path(__file__).resolve().parents[2]
api = biz.parent / "mom-mes-api/src/main/java/com/kitex/mom/mes/dto"
sources = [api / "WorkOrderStatisticsDTO.java", api / "ProcessDefectRateDTO.java",
           biz / "src/main/java/com/kitex/mom/mes/service/impl/MesStatisticsServiceImpl.java",
           biz / "src/test/java/com/kitex/mom/mes/service/impl/MesStatisticsServiceImplTest.java"]
classpath = args.classpath_file.read_text().strip()
with tempfile.TemporaryDirectory(prefix="mes-statistics-") as output:
    subprocess.run(["javac", "-cp", classpath, "-d", output, *map(str, sources)], check=True)
    result = subprocess.run(["java", "-cp", output + ":" + classpath,
                             "com.kitex.mom.mes.service.impl.MesStatisticsServiceImplTest"])
    sys.exit(result.returncode)
