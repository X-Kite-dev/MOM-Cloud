#!/usr/bin/env python3
from pathlib import Path
import subprocess,tempfile,sys
import argparse
parser=argparse.ArgumentParser(description='Run real dispatch history SQL and service tests in isolated output')
parser.add_argument('--classpath-file',required=True,type=Path)
args=parser.parse_args()
biz=Path(__file__).resolve().parents[2]
root=biz.parents[1]
cp=args.classpath_file.read_text().strip()
cp+=':'+str(Path.home()/'.m2/repository/com/h2database/h2/2.2.224/h2-2.2.224.jar')
api=root/'mom-mes/mom-mes-api/src/main/java/com/kitex/mom/mes'
main=biz/'src/main/java/com/kitex/mom/mes'
sources=list(api.glob('dto/MesDispatchHistory*.java'))+list(api.glob('entity/MesDispatchHistory*.java'))+list(main.glob('mapper/MesDispatchHistory*.java'))+list(main.glob('service/impl/MesDispatchHistory*.java'))+list(main.glob('controller/MesDispatchHistory*.java'))
sources += [main/'service/impl/MesExecutionCommandCancellationService.java',main/'mapper/MesExecutionCommandCancellationMapper.java',api/'entity/MesExecutionCommandCancellationEntity.java']
sources+=list((biz/'src/test/java/com/kitex/mom/mes/service/impl').glob('MesDispatchHistory*Test.java'))
sources += [main/'service/impl/MesActualFactoryAccessService.java',
            biz/'src/test/java/com/kitex/mom/mes/service/impl/MesFactoryAccessTestSupport.java']
common=root/'mom-common/mom-common-api/src/main/java/com/kitex/mom/common/barcode'
sources+=list(common.glob('utils/PrintTemplateFingerprint.java'))+list(common.glob('dto/req/PreviewObj.java'))
runner='import java.lang.reflect.*; public class RunHistory { public static void main(String[] args) throws Exception {int passed=0,failed=0;for(String name:args){Class<?> t=Class.forName("com.kitex.mom.mes.service.impl."+name);for(Method test:t.getDeclaredMethods()){if(!test.isAnnotationPresent(org.junit.jupiter.api.Test.class))continue;Constructor<?> c=t.getDeclaredConstructor();c.setAccessible(true);Object o=c.newInstance();try{for(Method m:t.getDeclaredMethods())if(m.isAnnotationPresent(org.junit.jupiter.api.BeforeEach.class)){m.setAccessible(true);m.invoke(o);}test.setAccessible(true);test.invoke(o);passed++;System.out.println("PASS "+test.getName());}catch(InvocationTargetException e){failed++;System.out.println("FAIL "+test.getName());e.getCause().printStackTrace();}finally{for(Method m:t.getDeclaredMethods())if(m.isAnnotationPresent(org.junit.jupiter.api.AfterEach.class)){m.setAccessible(true);m.invoke(o);}}}}System.out.println("Tests "+(passed+failed)+" passed "+passed+" failed "+failed);if(failed>0)System.exit(1);}}'
with tempfile.TemporaryDirectory(prefix='mes-history-') as out:
 helper=Path(out)/'RunHistory.java';helper.write_text(runner)
 result=subprocess.run(['javac','-proc:full','-cp',cp,'-d',out,*map(str,sources),str(helper)])
 if result.returncode:sys.exit(result.returncode)
 sys.exit(subprocess.run(['java','-cp',out+':'+str(biz/'src/main/resources')+':'+cp,'RunHistory',*([p.stem for p in sources if p.name.endswith('Test.java')])]).returncode)
