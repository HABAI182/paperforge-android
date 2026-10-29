"""Build and inspect installable candidates. Never creates a tag or publishes a release."""
from pathlib import Path
import argparse
import hashlib
import json
import os
import re
import shutil
import subprocess
import struct
import sys
import zipfile

ROOT = Path(__file__).resolve().parents[1]


def sha(path):
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def run(arguments, capture=False):
    result = subprocess.run([str(x) for x in arguments], cwd=ROOT, check=True,
                            capture_output=capture, text=True, encoding='utf-8', errors='replace')
    return result.stdout if capture else None


def inspect(apk, tools, java):
    badging = run([tools / ('aapt.exe' if os.name == 'nt' else 'aapt'), 'dump', 'badging', apk], True)
    package = re.search(r"package: name='([^']+)' versionCode='([^']+)' versionName='([^']+)'", badging)
    if not package:
        raise RuntimeError('APK package metadata is missing')
    signature = run([java, '-jar', tools / 'lib/apksigner.jar', 'verify', '--print-certs', apk], True)
    # New SDK tools label v3/v3.1 signers by minSdkVersion instead of ordinal.
    # The same identity may appear for multiple signing schemes; retain every distinct signer.
    certificates = sorted({digest.lower() for digest in re.findall(
        r'^Signer[^\r\n]* certificate SHA-256 digest: ([0-9a-f]{64})',
        signature, re.MULTILINE | re.IGNORECASE)})
    if not certificates:
        raise RuntimeError(f'APK certificate report was not recognized ({tools}): {signature[:5000]!r}')
    alignment = run([tools / ('zipalign.exe' if os.name == 'nt' else 'zipalign'), '-c', '-P', '16', '4', apk], True)
    permissions = re.findall(r"uses-permission[^:]*: name='([^']+)'", badging)
    with zipfile.ZipFile(apk) as archive:
        files = archive.infolist()
        native = sorted({x.filename.split('/')[1] for x in files if x.filename.startswith('lib/')})
        elf = []
        for entry in files:
            if not entry.filename.endswith('.so'):
                continue
            data = archive.read(entry)
            if data[:4] != b'\x7fELF':
                raise RuntimeError('Native library is not ELF: ' + entry.filename)
            endian = '<' if data[5] == 1 else '>'
            wide = data[4] == 2
            offset = struct.unpack_from(endian + ('Q' if wide else 'I'), data, 32 if wide else 28)[0]
            stride, count = struct.unpack_from(endian + 'HH', data, 54 if wide else 42)
            alignments = [struct.unpack_from(endian + ('Q' if wide else 'I'), data, offset + i * stride + (48 if wide else 28))[0]
                          for i in range(count) if struct.unpack_from(endian + 'I', data, offset + i * stride)[0] == 1]
            elf.append({'file': entry.filename, 'loadAlignments': alignments,
                        'compatible16KiB': bool(alignments) and min(alignments) >= 16384})
        groups = {}
        for entry in files:
            group = 'native/' + entry.filename.split('/')[1] if entry.filename.startswith('lib/') else entry.filename.split('/')[0]
            if group.endswith('.dex'):
                group = 'dex'
            value = groups.setdefault(group, {'zipBytes': 0, 'unpackedBytes': 0})
            value['zipBytes'] += entry.compress_size
            value['unpackedBytes'] += entry.file_size
        largest = [{'file': x.filename, 'zipBytes': x.compress_size, 'unpackedBytes': x.file_size}
                   for x in sorted(files, key=lambda x: x.compress_size, reverse=True)[:20]]
    return {'applicationId': package[1], 'versionCode': int(package[2]), 'versionName': package[3],
            'certificatesSha256': certificates, 'certificateReport': signature.strip(),
            'buildToolsVersion': tools.name, 'abis': native, 'permissions': permissions,
            'zipAlignment16KiB': True, 'bytes': apk.stat().st_size, 'sha256': sha(apk),
            'groups': groups, 'largestFiles': largest, 'nativeElf': elf,
            'nativeElf16KiBCompatible': all(x['compatible16KiB'] for x in elf)}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--abi', choices=['arm64-v8a', 'x86_64', 'universal'], default='arm64-v8a')
    parser.add_argument('--channel', choices=['development', 'prerelease', 'stable'], default='development')
    parser.add_argument('--include-engines', action='store_true')
    parser.add_argument('--engine', action='append', default=[],
                        choices=['office-engine', 'format-engine', 'network-engine'],
                        help='Include only the selected optional engine; repeat for multiple engines')
    parser.add_argument('--isolated-ci', action='store_true', help='Debug package cn.paperforge.app.ci, using a disposable CI identity')
    parser.add_argument('--output', type=Path)
    args = parser.parse_args()
    sdk = Path(os.environ.get('ANDROID_HOME') or os.environ.get('ANDROID_SDK_ROOT') or '')
    java = Path(os.environ['JAVA_HOME']) / 'bin' / ('java.exe' if os.name == 'nt' else 'java')
    policy = json.loads((ROOT / 'docs/development/distribution-policy.json').read_text(encoding='utf-8'))
    tools = sdk / 'build-tools' / policy['buildToolsVersion']
    if not tools.is_dir():
        raise SystemExit('Install Android SDK build-tools;' + policy['buildToolsVersion'])
    if not args.isolated_ci:
        key = Path(os.environ.get('PAPERFORGE_SIGNING_STORE', ''))
        if not key.is_file() or key.resolve().is_relative_to(ROOT):
            raise SystemExit('Set PAPERFORGE_SIGNING_STORE to the existing owner key outside this repository')
        for field in ['PAPERFORGE_STORE_PASSWORD', 'PAPERFORGE_KEY_ALIAS', 'PAPERFORGE_KEY_PASSWORD']:
            if not os.environ.get(field):
                raise SystemExit('Missing external signing setting: ' + field)
    output = (args.output or ROOT / 'build/distributions' / args.channel / args.abi).resolve()
    output.mkdir(parents=True, exist_ok=True)
    optional_modules = ['office-engine', 'format-engine', 'network-engine']
    modules = ['app'] + [module for module in optional_modules
                         if args.include_engines or module in args.engine]
    selected_abis = policy['supportedAbis'] if args.abi == 'universal' else [args.abi]
    if 'office-engine' in modules:
        run([sys.executable, '-X', 'utf8', 'tools/fetch_office_runtime.py',
             *sum((['--abi', abi] for abi in selected_abis), [])])
    variant = 'debug' if args.isolated_ci else 'release'
    command = [ROOT / ('gradlew.bat' if os.name == 'nt' else 'gradlew'),
               *[f':{module}:assemble{variant.title()}' for module in modules],
               '-PpaperforgeAbis=' + ','.join(selected_abis), '-Pksp.incremental=false', '--console=plain', '--build-cache']
    if args.isolated_ci:
        command += ['-PpaperforgeIdSuffix=.ci']
    run(command)
    results = []
    for module in modules:
        directory = ROOT / module / 'build/outputs/apk' / variant
        metadata = json.loads((directory / 'output-metadata.json').read_text(encoding='utf-8'))
        apk = directory / metadata['elements'][0]['outputFile']
        record = inspect(apk, tools, java)
        if not args.isolated_ci and record['certificatesSha256'] != [policy['ownerDevelopmentCertificateSha256']]:
            raise RuntimeError('Candidate does not continue the owner certificate; no artifact copied')
        expected_id = policy['applicationId'] + ('.ci' if args.isolated_ci else '')
        if module == 'app':
            if args.channel == 'stable' and '-' in record['versionName']:
                raise RuntimeError('A development/prerelease version cannot be labeled as stable')
            if record['applicationId'] != expected_id or 'android.permission.INTERNET' in record['permissions']:
                raise RuntimeError('Main package identity/offline permission contract changed')
        if record['abis'] and record['abis'] != sorted(selected_abis):
            raise RuntimeError('Unexpected native architectures: ' + str(record['abis']))
        label = {'app': 'PaperForge', 'office-engine': 'PaperForge-Office', 'format-engine': 'PaperForge-Formats', 'network-engine': 'PaperForge-Network'}[module]
        filename = f'{label}-{record["versionName"]}-{args.abi}-{args.channel}{"-isolated-ci" if args.isolated_ci else ""}.apk'
        target = output / filename
        shutil.copy2(apk, target)
        record.update({'file': filename, 'module': module, 'variant': variant})
        results.append(record)
    main_app = results[0]
    revision = run(['git', 'rev-parse', 'HEAD'], True).strip()
    dirty = bool(run(['git', 'status', '--porcelain'], True).strip())
    manifest = {'format': 1, 'candidateOnly': True, 'releaseHold': policy['releaseHold'],
                'channel': args.channel, 'abi': args.abi, 'commit': revision, 'workingTreeDirty': dirty,
                'versionCode': main_app['versionCode'], 'versionName': main_app['versionName'],
                'isolatedCi': args.isolated_ci, 'artifacts': results}
    (output / 'release-manifest.json').write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    checksums = ''.join(f'{record["sha256"]}  {record["file"]}\n' for record in results)
    (output / 'SHA256SUMS.txt').write_text(checksums, encoding='utf-8')
    print(json.dumps({'directory': str(output), 'candidateOnly': True,
                      'artifacts': [{'file': x['file'], 'bytes': x['bytes'], 'sha256': x['sha256']} for x in results]}))


if __name__ == '__main__':
    main()
