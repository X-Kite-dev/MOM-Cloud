#!/usr/bin/env python3
"""Compile real consumption-mode boundaries and run regressions without Maven or shared target writes."""
import argparse
from pathlib import Path
import subprocess
import sys
import tempfile

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("--classpath-file", required=True, type=Path)
args = parser.parse_args()
biz = Path(__file__).resolve().parents[2]
main = biz / "src/main/java/com/kitex/mom/mes"
sources = [main / "process/AbstractProcessOperation.java",
           main / "process/impl/QuantityProcessOperation.java",
           main / "service/impl/MesMaterialStartGateService.java",
           main / "service/impl/MesWarehouseFeedServiceImpl.java",
           main / "service/impl/MesWarehouseFeedPreparationReader.java",
           main / "service/impl/MesProductionMaterialOperationServiceImpl.java",
           main / "service/impl/MesProductionFeedBatchServiceImpl.java",
           biz / "src/test/java/com/kitex/mom/mes/service/impl/MesMaterialConsumptionModeTest.java"]
resolver = main / "service/impl/MesMaterialConsumptionModeResolver.java"
if resolver.exists():
    sources.append(resolver)
classpath = args.classpath_file.read_text().strip()
with tempfile.TemporaryDirectory(prefix="mes-material-mode-") as output:
    result = subprocess.run(["javac", "-proc:full", "-cp", classpath, "-d", output, *map(str, sources)])
    if result.returncode:
        sys.exit(result.returncode)
    result = subprocess.run(["java", "-XX:+EnableDynamicAgentLoading", "-cp", output + ":" + classpath,
                             "com.kitex.mom.mes.service.impl.MesMaterialConsumptionModeTest"])
    sys.exit(result.returncode)
