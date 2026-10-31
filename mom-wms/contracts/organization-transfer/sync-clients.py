"""Publish this versioned WMS operation contract to the two independent frontends."""
from pathlib import Path
import hashlib

contract = Path(__file__).resolve().parent
code = contract.parents[3]
targets = [code / 'Mom-APP/src/features/wms/organization-transfer',
           code / 'Mom-UI/apps/mom-wms-ui/src/features/organizationTransfer']
for name in ('contract.ts', 'operation.ts', 'model.ts', 'workspace.ts'):
    source = (contract / name).read_bytes()
    digest = hashlib.sha256(source).hexdigest()
    header = f'// Generated from Mom-Cloud/mom-wms/contracts/organization-transfer/{name}; source SHA256: {digest}\n'
    for target in targets:
        target.mkdir(parents=True, exist_ok=True)
        client_header = header
        if 'Mom-UI' in str(target) and name == 'contract.ts':
            client_header += '/* eslint-disable unicorn/prefer-structured-clone -- Preserve JSON transport semantics and compatibility with handheld clients. */\n'
        (target / name).write_text(client_header + source.decode('utf-8'), encoding='utf-8')
print('Published organization-transfer contract and operation session to APP and PC')
