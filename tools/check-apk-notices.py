#!/usr/bin/env python3
from pathlib import Path
import zipfile
root=Path(__file__).resolve().parents[1]
source=root/'app/src/main/assets/licenses/THIRD-PARTY-NOTICES.txt'
paths=[]
for folder in ['app/build/outputs/apk/standard/release','app/build/outputs/apk/guard/release','keybridge/build/outputs/apk/release']:
    candidates=list((root/folder).glob('*-unsigned.apk'))
    assert len(candidates)==1,folder
    paths+=candidates
for path in paths:
    with zipfile.ZipFile(path) as archive:
        assert archive.read('assets/licenses/THIRD-PARTY-NOTICES.txt')==source.read_bytes(),path
        assert not any(n.startswith('META-INF/') and n.endswith(('.RSA','.DSA','.EC')) for n in archive.namelist()),path
    print('APK notice bundle verified:',path.name)
