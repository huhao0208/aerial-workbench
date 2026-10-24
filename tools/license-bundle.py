#!/usr/bin/env python3
"""Build/check bundled notices from exact resolved runtime artifacts and cached POMs."""
import argparse
import hashlib
import io
import json
import re
from pathlib import Path
import xml.etree.ElementTree as ET
import zipfile

ROOT = Path(__file__).resolve().parents[1]
NS = {'m': 'http://maven.apache.org/POM/4.0.0'}

def archive_notices(path):
    result = {}
    def visit(data, prefix=''):
        with zipfile.ZipFile(data) as archive:
            for name in sorted(archive.namelist()):
                if name.endswith('/'):
                    continue
                if re.search(r'(?:^|/)(?:license|notice|copying|copyright)(?:[./_-]|$)', name, re.I):
                    result[prefix+name] = archive.read(name).decode('utf-8', errors='replace')
                elif name == 'classes.jar' or (name.startswith('libs/') and name.endswith('.jar')):
                    visit(io.BytesIO(archive.read(name)), prefix+name+'!/')
    visit(path)
    return result

def pom_licenses(cache, group, name, version, seen=None):
    seen = set() if seen is None else seen
    coordinate = (group, name, version)
    if coordinate in seen:
        raise ValueError('Cyclic POM parents')
    seen.add(coordinate)
    poms = sorted((cache/group/name/version).rglob('*.pom'))
    if not poms:
        raise ValueError('Missing POM: '+':'.join(coordinate))
    root = ET.parse(poms[0]).getroot()
    result = [{'name': item.findtext('m:name', namespaces=NS), 'url': item.findtext('m:url', namespaces=NS)}
              for item in root.findall('m:licenses/m:license', NS)]
    if result:
        return result
    parent = root.find('m:parent', NS)
    if parent is None:
        raise ValueError('Unresolved license: '+':'.join(coordinate))
    return pom_licenses(cache, *[parent.findtext('m:'+key, namespaces=NS) for key in ['groupId','artifactId','version']], seen)

def generate(inputs):
    entries, notices = [], []
    for row in inputs.read_text().splitlines():
        group, name, version, filename = row.split('\t')
        path = Path(filename)
        cache = path.parents[4]
        coordinate = ':'.join([group, name, version])
        entries.append({'coordinate': coordinate, 'sha256': hashlib.sha256(path.read_bytes()).hexdigest(),
                        'licenses': pom_licenses(cache, group, name, version)})
        notices += ['\n## '+coordinate+' / '+key+'\n\n'+value for key, value in archive_notices(path).items()]
    metadata = json.dumps(entries, ensure_ascii=False, indent=2)+'\n'
    body = (ROOT/'NOTICE.md').read_text()+'\n\n# Full license texts\n'
    for name in ['LICENSE', 'FONT-LICENSE-OFL.txt']:
        body += '\n## '+name+'\n\n'+(ROOT/name).read_text()
    for path in sorted((ROOT/'licenses').glob('*.txt')):
        body += '\n## '+path.name+'\n\n'+path.read_text()
    body += '\n\n# Runtime artifact metadata\n\n'+metadata+'\n# Embedded dependency notices\n'+''.join(notices)
    return metadata, body

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--inputs', type=Path, default=ROOT/'build/license-inputs.tsv')
    parser.add_argument('--check', action='store_true')
    args = parser.parse_args()
    metadata, body = generate(args.inputs)
    outputs = [(ROOT/'licenses/runtime-dependencies.json', metadata),
               (ROOT/'app/src/main/assets/licenses/THIRD-PARTY-NOTICES.txt', body),
               (ROOT/'keybridge/src/main/assets/licenses/THIRD-PARTY-NOTICES.txt', body)]
    for path, content in outputs:
        if args.check:
            if not path.exists() or path.read_text() != content:
                raise SystemExit('License bundle drift: '+str(path.relative_to(ROOT)))
        else:
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(content)
    print('Verified' if args.check else 'Generated', len(json.loads(metadata)), 'runtime artifact notices; no local paths recorded')

if __name__ == '__main__': main()
