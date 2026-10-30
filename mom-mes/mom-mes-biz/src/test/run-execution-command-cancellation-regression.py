#!/usr/bin/env python3
"""Run actual cancellation mapper/H2/Spring regressions without Maven or shared target writes."""
import argparse
from pathlib import Path
import subprocess
import sys
import tempfile

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("--classpath-file", required=True, type=Path)
args = parser.parse_args()
biz = Path(__file__).resolve().parents[2]
api = biz.parent / "mom-mes-api/src/main/java/com/kitex/mom/mes"
main = biz / "src/main/java/com/kitex/mom/mes"
sources = [api / "entity/MesExecutionCommandCancellationEntity.java",
           main / "mapper/MesExecutionCommandCancellationMapper.java",
           main / "service/impl/MesExecutionCommandCancellationService.java",
           main / "service/impl/MesActualFactoryAccessService.java",
           biz / "src/test/java/com/kitex/mom/mes/service/impl/MesExecutionCommandCancellationServiceTest.java"]
sources += [biz / "src/test/java/com/kitex/mom/mes/service/impl/MesFactoryAccessTestSupport.java"]
classpath = args.classpath_file.read_text().strip()
classpath += ":" + str(Path.home() / ".m2/repository/com/h2database/h2/2.2.224/h2-2.2.224.jar")
with tempfile.TemporaryDirectory(prefix="mes-execution-cancellation-") as output:
    compiled = subprocess.run(["javac", "-proc:full", "-cp", classpath, "-d", output, *map(str, sources)])
    if compiled.returncode:
        sys.exit(compiled.returncode)
    result = subprocess.run(["java", "-cp", output + ":" + classpath,
                             "com.kitex.mom.mes.service.impl.MesExecutionCommandCancellationServiceTest"])
    sys.exit(result.returncode)
