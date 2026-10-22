from pathlib import Path
import hashlib, json, shutil, xml.etree.ElementTree as ET
from datetime import datetime, timezone, timedelta

root=Path(__file__).resolve().parents[1]
out=root/'docs/acceptance'; out.mkdir(parents=True,exist_ok=True)
sources={
    'unit-tests.xml':root/'app/build/test-results/testDebugUnitTest/TEST-cn.paperforge.app.GeometryTest.xml',
    'device-tests.xml':next((root/'app/build/outputs/androidTest-results/connected/debug').glob('TEST-*.xml')),
    'lint-results-debug.txt':root/'app/build/reports/lint-results-debug.txt',
    'lint-results-debug.xml':root/'app/build/reports/lint-results-debug.xml',
}
for name,p in sources.items(): shutil.copy2(p,out/name)
def counts(name):
    r=ET.parse(out/name).getroot()
    return {k:int(r.attrib.get(k,0)) for k in ['tests','failures','errors','skipped']}
issues=ET.parse(out/'lint-results-debug.xml').getroot().findall('issue')
apk=root/'app/build/outputs/apk/debug/app-debug.apk'
summary={
    'collected_at':datetime.now(timezone(timedelta(hours=8))).isoformat(),
    'version':'0.1.0','application_id':'cn.paperforge.app','min_sdk':26,'target_sdk':36,
    'abis':['arm64-v8a','x86_64'],'signing':'Android debug signing key',
    'apk_bytes':apk.stat().st_size,'apk_sha256':hashlib.sha256(apk.read_bytes()).hexdigest(),
    'unit_tests':counts('unit-tests.xml'),'device_tests':counts('device-tests.xml'),
    'lint':{'errors':sum(i.get('severity') in ['Error','Fatal'] for i in issues),'warnings':sum(i.get('severity')=='Warning' for i in issues)},
    'device':'paperforge_api35 / Android 15 API35 / AOSP x86_64 / no GMS / airplane mode',
    'physical_device_tested':False,
    'command':'gradlew.bat assembleDebug testDebugUnitTest lintDebug connectedDebugAndroidTest --continue --console=plain',
}
assert summary['unit_tests']['failures']==0 and summary['device_tests']['failures']==0
assert summary['lint']['errors']==0
(out/'summary.json').write_text(json.dumps(summary,ensure_ascii=False,indent=2),encoding='utf-8')
print(json.dumps(summary,ensure_ascii=False,indent=2))
