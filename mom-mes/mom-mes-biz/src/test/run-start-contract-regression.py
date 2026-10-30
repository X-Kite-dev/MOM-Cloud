#!/usr/bin/env python3
"""Compile real start/preparation regression tests without writing shared Maven target directories.

Usage: python3 src/test/run-start-contract-regression.py --classpath-file /tmp/mes-stats-testclasspath.txt
The explicit runner is needed because the existing Surefire 2.12.4 skips Jupiter tests.
"""
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
sources = [main / "service/impl/MesTaskArrangeServiceImpl.java",
           main / "agent/service/MesStartPreparationQueryService.java",
           biz / "src/test/java/com/kitex/mom/mes/service/impl/MesStartContractGuardTest.java",
           biz.parents[1] / "mom-tpm/mom-tpm-api/src/main/java/com/kitex/mom/tpm/dto/DeviceCheckTaskStartDto.java"]
api = biz.parent / "mom-mes-api/src/main/java/com/kitex/mom/mes"
sources += list(api.glob("dto/MesWorkpieceTime*.java")) + list(api.glob("entity/MesWorkpieceTime*.java"))
sources += list(main.glob("mapper/MesWorkpieceTime*.java")) + list(main.glob("service/impl/MesWorkpieceTime*.java"))
sources += [api / "entity/MesTerminalExecutionCommandEntity.java"]
sources += [api / "dto/MesExecutionContractDTO.java", api / "agent/dto/MesStartPreparationDTO.java",
            api / "entity/MesStartPreparationCheck.java"]
sources += [api / "dto/MesIdentifierNodePolicyDTO.java", api / "dto/MesPhysicalTransformPolicyContractDTO.java",
            api / "entity/MesExecutionCommandCancellationEntity.java",
            main / "mapper/MesExecutionCommandCancellationMapper.java",
            main / "service/impl/MesExecutionCommandCancellationService.java"]
guard = main / "service/impl/MesStartContractGuard.java"
if guard.exists():
    sources.append(guard)
classpath = args.classpath_file.read_text().strip()
with tempfile.TemporaryDirectory(prefix="mes-start-contract-") as output:
    compiled = subprocess.run(["javac", "-proc:full", "-cp", classpath, "-d", output, *map(str, sources)])
    if compiled.returncode:
        sys.exit(compiled.returncode)
    result = subprocess.run(["java", "-XX:+EnableDynamicAgentLoading", "-cp", output + ":" + classpath,
                             "com.kitex.mom.mes.service.impl.MesStartContractGuardTest"])
    sys.exit(result.returncode)
