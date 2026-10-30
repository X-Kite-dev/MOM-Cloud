#!/usr/bin/env python3
"""Compile the real Common print classes in a private directory; never modify Maven targets."""
import argparse
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("--classpath-file", type=Path, required=True)
args = parser.parse_args()
biz = Path(__file__).resolve().parents[2]
api = biz.parent / "mom-common-api/src/main/java/com/kitex/mom/common/barcode"
main = biz / "src/main/java/com/kitex/mom/common/barcode"
sources = [main / "utils/VelocityKit.java", main / "template/HtmlTemplate.java",
           main / "controller/PrintController.java", main / "controller/PrintTemplateController.java",
           main / "controller/PrintLogController.java",
           main / "service/impl/PrintTemplateServiceImpl.java", api / "dto/req/PreviewObj.java",
           api / "entity/PrintTemplateEntity.java",
           biz / "src/test/java/com/kitex/mom/common/barcode/CommonPrintRegressionTest.java"]
sources.append(biz.parents[2] / "Mom-Base/mom-common/mom-common-core/src/main/java/com/kitex/common/core/util/R.java")
fingerprint = api / "utils/PrintTemplateFingerprint.java"
if fingerprint.exists():
    sources.append(fingerprint)
# The shared MES classpath includes unrelated logging providers; keep the real Logback binding only.
classpath = ":".join(part for part in args.classpath_file.read_text().strip().split(":")
                     if "/tlog-adapter/" not in part and "/commons-logging/" not in part)
repository = Path.home() / ".m2/repository"
for dependency in ["org/apache/velocity/velocity-engine-core/2.3/velocity-engine-core-2.3.jar",
                   "com/google/zxing/core/3.3.1/core-3.3.1.jar",
                   "org/apache/velocity/tools/velocity-tools-generic/3.1/velocity-tools-generic-3.1.jar"]:
    jar = repository / dependency
    if not jar.exists():
        parser.error("Required cached dependency is unavailable: " + str(jar))
    classpath += ":" + str(jar)
with tempfile.TemporaryDirectory(prefix="common-print-regression-") as output:
    shutil.copytree(biz / "src/main/resources/static", Path(output) / "static")
    shutil.copytree(biz / "src/test/resources", output, dirs_exist_ok=True)
    result = subprocess.run(["javac", "-proc:full", "-cp", classpath, "-d", output, *map(str, sources)])
    if result.returncode:
        sys.exit(result.returncode)
    result = subprocess.run(["java", "-XX:+EnableDynamicAgentLoading", "-cp", output + ":" + classpath,
                             "com.kitex.mom.common.barcode.CommonPrintRegressionTest", str(biz)])
    sys.exit(result.returncode)
