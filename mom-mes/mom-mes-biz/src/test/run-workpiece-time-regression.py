#!/usr/bin/env python3
"""Run real workpiece SQL/transaction tests in an isolated output directory."""
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
sources = list(api.glob("dto/MesWorkpieceTime*.java")) + list(api.glob("entity/MesWorkpieceTime*.java"))
sources += [api / "entity/MesTerminalExecutionCommandEntity.java"]
sources += [api / "dto/MesExecutionContractDTO.java", api / "agent/dto/MesStartPreparationDTO.java",
            api / "entity/MesStartPreparationCheck.java"]
sources += [api / "dto/MesIdentifierNodePolicyDTO.java", api / "dto/MesPhysicalTransformPolicyContractDTO.java",
            api / "entity/MesExecutionCommandCancellationEntity.java",
            main / "mapper/MesExecutionCommandCancellationMapper.java",
            main / "service/impl/MesExecutionCommandCancellationService.java"]
sources += list(main.glob("mapper/MesWorkpieceTime*.java")) + list(main.glob("service/impl/MesWorkpieceTime*.java"))
sources += list(main.glob("controller/MesWorkpieceTime*.java"))
sources += list((biz / "src/test/java/com/kitex/mom/mes/service/impl").glob("MesWorkpieceTime*Test.java"))
sources += [main / "service/impl/MesTaskArrangeServiceImpl.java", main / "service/impl/MesStartContractGuard.java",
            main / "agent/service/MesStartPreparationQueryService.java",
            biz / "src/test/java/com/kitex/mom/mes/service/impl/MesStartContractGuardTest.java",
            biz.parents[1] / "mom-tpm/mom-tpm-api/src/main/java/com/kitex/mom/tpm/dto/DeviceCheckTaskStartDto.java"]
sources += [main / "service/impl/MesActualFactoryAccessService.java",
            biz / "src/test/java/com/kitex/mom/mes/service/impl/MesFactoryAccessTestSupport.java"]
classpath = args.classpath_file.read_text().strip()
h2 = Path.home() / ".m2/repository/com/h2database/h2/2.2.224/h2-2.2.224.jar"
classpath += ":" + str(h2)
runner = """import java.lang.reflect.*;
public class WorkpieceTestRunner {
 public static void main(String[] args) throws Exception {
  Class<?> type=Class.forName("com.kitex.mom.mes.service.impl.MesWorkpieceTimeServiceTest");
  int passed=0,failed=0;
  for(Method test:type.getDeclaredMethods()) {
   if(!test.isAnnotationPresent(org.junit.jupiter.api.Test.class)) continue;
   Constructor<?> ctor=type.getDeclaredConstructor();ctor.setAccessible(true);Object instance=ctor.newInstance();
   try {
    for(Method m:type.getDeclaredMethods()) if(m.isAnnotationPresent(org.junit.jupiter.api.BeforeEach.class)){m.setAccessible(true);m.invoke(instance);}
    test.setAccessible(true);test.invoke(instance);passed++;System.out.println("PASS "+test.getName());
   } catch(InvocationTargetException e){failed++;System.err.println("FAIL "+test.getName());e.getCause().printStackTrace();}
   finally {for(Method m:type.getDeclaredMethods())if(m.isAnnotationPresent(org.junit.jupiter.api.AfterEach.class)){m.setAccessible(true);m.invoke(instance);}}
  }
  System.out.println("Tests: "+(passed+failed)+", passed: "+passed+", failed: "+failed);
  if(failed>0)System.exit(1);
 }
}
"""
with tempfile.TemporaryDirectory(prefix="mes-workpiece-time-") as output:
    helper = Path(output) / "WorkpieceTestRunner.java"
    helper.write_text(runner)
    compiled = subprocess.run(["javac", "-proc:full", "-cp", classpath, "-d", output, *map(str, sources), str(helper)])
    if compiled.returncode:
        sys.exit(compiled.returncode)
    sys.exit(subprocess.run(["java", "-cp", output + ":" + classpath, "WorkpieceTestRunner"]).returncode)
