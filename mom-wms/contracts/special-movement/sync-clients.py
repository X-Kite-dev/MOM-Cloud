"""Publish this versioned WMS operation contract to the two independent frontends."""
from pathlib import Path
import hashlib

contract = Path(__file__).resolve().parent
code = contract.parents[3]
targets = [code / 'Mom-APP/src/features/wms/special-movement',
           code / 'Mom-UI/apps/mom-wms-ui/src/features/specialMovement']
for name in ('contract.ts', 'operation.ts', 'model.ts', 'workspace.ts'):
    source = (contract / name).read_bytes()
    digest = hashlib.sha256(source).hexdigest()
    header = f'// Generated from Mom-Cloud/mom-wms/contracts/special-movement/{name}; source SHA256: {digest}\n'
    for target in targets:
        target.mkdir(parents=True, exist_ok=True)
        client_header = header
        (target / name).write_text(client_header + source.decode('utf-8'), encoding='utf-8')
print('Published special-movement contract and operation session to APP and PC')
