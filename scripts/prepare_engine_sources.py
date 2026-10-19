import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import tarfile

ROOT = Path(__file__).resolve().parents[1]
CONFIG = json.loads((ROOT / 'scripts/engine_sources.json').read_text())
BUILD = ROOT / 'app/build/engines'
SOURCES = BUILD / 'sources'
CACHE = BUILD / 'downloads'
ASSETS = ROOT / 'app/build/generated/engineAssets/engines'
RELEASE = ROOT / 'app/build/engine-release'
for directory in (SOURCES, CACHE, ASSETS, RELEASE):
    directory.mkdir(parents=True, exist_ok=True)


def sha256(path):
    digest = hashlib.sha256()
    with path.open('rb') as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b''):
            digest.update(chunk)
    return digest.hexdigest()


def download(url, name):
    destination = CACHE / name
    if not destination.exists():
        temporary = destination.with_suffix(destination.suffix + '.part')
        subprocess.run(['curl', '--fail', '--location', '--retry', '2', '--connect-timeout', '20',
                        '--max-time', '1200', '--proto', '=https', '--proto-redir', '=https',
                        '--output', str(temporary), url], check=True)
        temporary.replace(destination)
    return destination


archives = []
catalog = []
for engine in CONFIG['engines']:
    identity = engine['id']
    commit = engine['commit']
    archive = download(f'https://codeload.github.com/{engine["repo"]}/tar.gz/{commit}', f'{identity}-{commit}.tar.gz')
    archives.append(archive)
    destination = SOURCES / identity
    if not destination.exists():
        unpack = BUILD / ('unpack-' + identity)
        unpack.mkdir(exist_ok=True)
        with tarfile.open(archive) as tar:
            tar.extractall(unpack, filter='data')
        children = list(unpack.iterdir())
        if len(children) != 1 or not children[0].is_dir():
            raise RuntimeError('Unexpected source archive layout: ' + identity)
        children[0].rename(destination)
        (destination / '.source-revision').write_text(commit)
    if (destination / '.source-revision').read_text() != commit:
        raise RuntimeError('Engine source cache does not match pinned revision')
    if identity == 'fairystockfish':
        makefile = destination / 'src/Makefile'
        original = makefile.read_text()
        obsolete = ' -fexperimental-new-pass-manager'
        if obsolete in original:
            if original.count(obsolete) != 1:
                raise RuntimeError('Unexpected Fairy-Stockfish compiler flags')
            makefile.write_text(original.replace(obsolete, ''))
    licenses = ASSETS / 'licenses' / identity
    licenses.mkdir(parents=True, exist_ok=True)
    for filename in ('Copying.txt', 'LICENSE', 'AUTHORS', 'CONTRIBUTORS'):
        source = destination / filename
        if source.is_file():
            shutil.copy2(source, licenses / filename)
    if identity == 'katago':
        external = destination / 'cpp/external'
        for source in external.rglob('*'):
            if source.is_file() and source.name.lower().startswith(('license', 'copying')):
                target = licenses / 'external' / source.relative_to(external)
                target.parent.mkdir(parents=True, exist_ok=True)
                shutil.copy2(source, target)
    models = []
    if identity == 'pikafish':
        packed = download(engine['modelArchive'], 'pikafish-2025-06-23.7z')
        if sha256(packed) != engine['archiveSha256']:
            raise RuntimeError('Pikafish release checksum mismatch')
        listing = subprocess.check_output(['7z', 'l', '-slt', str(packed)], text=True)
        names = [line[7:] for line in listing.splitlines() if line.startswith('Path = ') and line.endswith('pikafish.nnue')]
        if len(names) != 1:
            raise RuntimeError('Expected exactly one version-matched Pikafish model')
        path = RELEASE / 'pikafish.nnue'
        with path.open('wb') as stream:
            subprocess.run(['7z', 'x', '-so', str(packed), names[0]], stdout=stream, check=True)
        source_model = destination / 'src/pikafish.nnue'
        shutil.copy2(path, source_model)
        sha = os.environ.get('GITHUB_SHA') or subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=ROOT, text=True).strip()
        repo = os.environ.get('GITHUB_REPOSITORY', 'navy2016/chess-thinking-coach')
        model_url = f'https://github.com/{repo}/releases/download/v1.0-{sha[:7]}/pikafish.nnue'
        models.append(dict(name='pikafish.nnue', url=model_url, sha256=sha256(path), bytes=path.stat().st_size))
    else:
        for model in engine['models']:
            path = download(model['url'], model['name'])
            digest = sha256(path)
            if identity == 'stockfish' and not digest.startswith(model['name'][3:15]):
                raise RuntimeError('Stockfish model prefix checksum mismatch')
            models.append(dict(model, sha256=digest, bytes=path.stat().st_size))
            if identity == 'stockfish':
                shutil.copy2(path, destination / 'src' / model['name'])
    catalog.append(dict(id=identity, binary=identity, games=engine['games'], name=engine['name'], version=engine['version'],
                        commit=commit, license=engine['license'], models=models))
    for alternative in engine.get('alternatives', []):
        alternative_models = []
        for model in alternative['models']:
            path = download(model['url'], model['name'])
            alternative_models.append(dict(model, sha256=sha256(path), bytes=path.stat().st_size))
        catalog.append(dict(id=alternative['id'], binary=identity, games=engine['games'], name=engine['name'],
                            version=alternative['version'], commit=commit, license=alternative['license'], models=alternative_models))

# Preserve the exact notices that cover separately downloaded model data.
for name, url in [('pikafish-models.txt', 'https://raw.githubusercontent.com/official-pikafish/Networks/master/README.md'),
                  ('katago-g170.txt', 'https://katagoarchive.org/g170/LICENSE.txt'),
                  ('katago-network.html', 'https://katagotraining.org/network_license/')]:
    shutil.copy2(download(url, name), ASSETS / 'licenses' / name)

eigen = SOURCES / 'eigen'
if not eigen.exists():
    subprocess.run(['git', 'clone', '--depth', '1', '--branch', CONFIG['eigen']['version'], CONFIG['eigen']['repo'], str(eigen)], check=True)
if subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=eigen, text=True).strip() != CONFIG['eigen']['commit']:
    raise RuntimeError('Eigen revision mismatch')
for source in eigen.glob('COPYING*'):
    shutil.copy2(source, ASSETS / 'licenses' / ('eigen-' + source.name))
ndk = Path(os.environ.get('ANDROID_NDK_HOME') or (str(Path(os.environ['ANDROID_HOME']) / 'ndk' / CONFIG['ndk'])))
notices = list(ndk.glob('NOTICE*'))
if not notices:
    raise RuntimeError('NDK runtime notice was not found')
for notice in notices:
    if notice.is_file():
        shutil.copy2(notice, ASSETS / 'licenses' / ('android-ndk-' + notice.name))
(ASSETS / 'catalog.json').write_text(json.dumps({'engines': catalog}, indent=2) + '\n')
with tarfile.open(RELEASE / 'engine-corresponding-sources.tar.gz', 'w:gz') as tar:
    for source in archives:
        tar.add(source, arcname=source.name)
    tar.add(eigen, arcname='eigen-3.4.0', filter=lambda info: None if '/.git' in info.name else info)
    for name in ('engine_sources.json', 'build_android_engines.sh', 'prepare_engine_sources.py'):
        tar.add(ROOT / 'scripts' / name, arcname='scripts/' + name)
    tar.add(ASSETS / 'licenses', arcname='licenses')
    tar.add(ROOT / 'app/src/main/java/com/sanqi/coach/tts', arcname='speech/kotlin')
    tar.add(ROOT / 'app/src/main/assets/speech', arcname='speech/notices')
print('Prepared pinned sources and model catalog:', ASSETS / 'catalog.json')
