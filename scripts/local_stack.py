#!/usr/bin/env python3
"""Local deployment manager. Owns only its PID files and never deletes database volumes."""
import argparse
import fcntl
import json
import os
from pathlib import Path
import secrets
import shutil
import signal
import socket
import subprocess
import sys
import time
import urllib.request

ROOT = Path(__file__).resolve().parent.parent
LOCAL = ROOT / '.local'
ENV = LOCAL / 'stack.env'
STATE = LOCAL / 'processes.json'
RELEASE = LOCAL / 'release.json'

def init():
    LOCAL.mkdir(mode=0o700, exist_ok=True)
    if not ENV.exists():
        values = {'PAGETUNER_LOCAL_USER': 'local-reader', 'PAGETUNER_LOCAL_PASSWORD': secrets.token_urlsafe(24),
                  'PAGETUNER_DATABASE_PASSWORD': secrets.token_urlsafe(24)}
        with ENV.open('x') as stream:
            os.chmod(ENV, 0o600)
            stream.write(''.join(f'{k}={v}\n' for k, v in values.items()))
    return dict(line.split('=', 1) for line in ENV.read_text().splitlines() if line and not line.startswith('#'))

def read(path):
    return json.loads(path.read_text()) if path.exists() else {}

def write(path, data):
    temp = path.with_suffix('.tmp')
    temp.write_text(json.dumps(data, indent=2))
    temp.replace(path)

def run(args, **kwargs):
    subprocess.run(args, cwd=ROOT, check=True, **kwargs)

def docker_ready():
    try:
        return subprocess.run(['docker', 'info'], stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL, timeout=3).returncode == 0
    except subprocess.TimeoutExpired:
        return False


def ensure_docker():
    """Recover a stopped local Docker Desktop before building or starting services."""
    if shutil.which('docker') is None:
        raise RuntimeError('Docker CLI is missing. Install Docker Desktop before running the local stack.')
    if docker_ready():
        return
    if sys.platform != 'darwin':
        raise RuntimeError('Docker is not ready. Start the Docker daemon and retry.')
    print('Docker is not ready; starting Docker Desktop (up to 90 seconds).', flush=True)
    try:
        subprocess.run(['open', '-g', '-a', 'Docker'], check=True, timeout=10,
            stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    except (subprocess.CalledProcessError, subprocess.TimeoutExpired) as error:
        raise RuntimeError('Could not start Docker Desktop. Open it manually and retry.') from error
    deadline = time.monotonic() + 90
    while time.monotonic() < deadline:
        if docker_ready():
            return
        time.sleep(2)
    raise RuntimeError('Docker did not become ready within 90 seconds. Check Docker Desktop and the active Docker context; retry up.')


def compose(*args):
    run(['docker', 'compose', '--env-file', str(ENV), '-f', str(ROOT / 'deploy/compose.local.yml'), *args])

def listening(port):
    with socket.socket() as sock:
        sock.settimeout(1)
        return sock.connect_ex(('127.0.0.1', port)) == 0

def healthy(url):
    try:
        with urllib.request.urlopen(url, timeout=3) as response:
            return response.status == 200
    except Exception:
        return False

def owns(record):
    result = subprocess.run(['ps', '-p', str(record['pid']), '-o', 'command='], capture_output=True, text=True)
    birth = subprocess.run(['ps', '-p', str(record['pid']), '-o', 'lstart='], capture_output=True, text=True)
    return result.returncode == 0 and birth.stdout.strip() == record.get('started')

def stop():
    state = read(STATE)
    for name, record in list(state.items()):
        if owns(record):
            os.kill(record['pid'], signal.SIGTERM)
            for _ in range(40):
                if not owns(record):
                    break
                time.sleep(.25)
            else:
                raise RuntimeError(f'{name} did not stop gracefully; inspect .local logs.')
        state.pop(name)
        write(STATE, state)

def prepare_release():
    run([sys.executable, '-m', 'unittest', 'discover', '-s', 'scripts/tests', '-v'])
    subprocess.run(['npm', 'test'], cwd=ROOT / 'frontend', check=True)
    run(['./gradlew', '-PbuildTarget=server', ':server:bootJar', 'verifyModuleBoundaries', '--console=plain'])
    subprocess.run(['npm', 'run', 'build'], cwd=ROOT / 'frontend', check=True)
    target = LOCAL / 'releases' / time.strftime('%Y%m%d-%H%M%S')
    target.mkdir(parents=True)
    shutil.copy2(ROOT / 'server/build/libs/server.jar', target / 'server.jar')
    standalone = ROOT / 'frontend/.next/standalone'
    shutil.copytree(standalone, target / 'frontend')
    candidates = [target / 'frontend/server.js', target / 'frontend/frontend/server.js']
    entry = next((p for p in candidates if p.exists()), None)
    if entry is None:
        raise RuntimeError('Next standalone entry was not found. Previous release is unchanged.')
    shutil.copytree(ROOT / 'frontend/.next/static', entry.parent / '.next/static', dirs_exist_ok=True)
    shutil.copytree(ROOT / 'frontend/public', entry.parent / 'public', dirs_exist_ok=True)
    return {'server': str(target / 'server.jar'), 'frontend': str(entry), 'createdAt': time.strftime('%Y-%m-%dT%H:%M:%S%z')}

def start(release):
    config = init()
    ensure_docker()
    compose('up', '-d', '--wait')
    state = read(STATE)
    java_home = subprocess.check_output(['/usr/libexec/java_home', '-v', '21'], text=True).strip()
    services = {
        'server': (8080, [str(Path(java_home) / 'bin/java'), '-Xmx512m', '-jar', release['server']], {
            **config, 'PAGETUNER_DATABASE_URL': 'jdbc:postgresql://127.0.0.1:55432/pagetuner',
            'PAGETUNER_DATABASE_USER': 'pagetuner', 'PAGETUNER_SERVER_ADDRESS': '127.0.0.1'},
            'http://127.0.0.1:8080/actuator/health'),
        'frontend': (3000, [shutil.which('node'), release['frontend']], {'HOSTNAME': '127.0.0.1', 'PORT': '3000', 'NODE_ENV': 'production'},
            'http://127.0.0.1:3000'),
    }
    for name, (port, command, env, url) in services.items():
        if name in state and owns(state[name]) and healthy(url):
            continue
        if listening(port):
            raise RuntimeError(f'Port {port} is occupied by an unmanaged service. It was not stopped.')
        with (LOCAL / f'{name}.log').open('ab') as log:
            process = subprocess.Popen(command, cwd=ROOT, env={**os.environ, **env}, stdin=subprocess.DEVNULL,
                stdout=log, stderr=subprocess.STDOUT, start_new_session=True)
        started = subprocess.check_output(['ps', '-p', str(process.pid), '-o', 'lstart='], text=True).strip()
        state[name] = {'pid': process.pid, 'marker': release[name], 'started': started}
        write(STATE, state)
        for _ in range(60):
            if healthy(url):
                break
            if process.poll() is not None:
                raise RuntimeError(f'{name} exited. Inspect .local/{name}.log')
            time.sleep(1)
        else:
            raise RuntimeError(f'{name} health check timed out.')
    print('Frontend: http://127.0.0.1:3000 | API: http://127.0.0.1:8080 | DB: 127.0.0.1:55432')
    print('Credentials are stored in .local/stack.env (not printed).')

def verify_candidate(release):
    """Keep the active release intact until the candidate passes real browser regression."""
    if listening(3110):
        raise RuntimeError('Candidate port 3110 is occupied. Current deployment was preserved.')
    with (LOCAL / 'candidate.log').open('ab') as log:
        process = subprocess.Popen([shutil.which('node'), release['frontend']], cwd=ROOT,
            env={**os.environ, 'HOSTNAME': '127.0.0.1', 'PORT': '3110', 'NODE_ENV': 'production'},
            stdin=subprocess.DEVNULL, stdout=log, stderr=subprocess.STDOUT, start_new_session=True)
    try:
        for _ in range(60):
            if healthy('http://127.0.0.1:3110'):
                break
            if process.poll() is not None:
                raise RuntimeError('Candidate exited; inspect .local/candidate.log. Current deployment was preserved.')
            time.sleep(.5)
        else:
            raise RuntimeError('Candidate health check timed out. Current deployment was preserved.')
        subprocess.run(['npm', 'run', 'test:e2e'], cwd=ROOT / 'frontend', check=True,
            env={**os.environ, 'E2E_PRODUCTION': '1', 'E2E_BASE_URL': 'http://127.0.0.1:3110'})
    finally:
        if process.poll() is None:
            process.terminate()
            process.wait(timeout=30)


def deploy():
    ensure_docker()
    previous = read(RELEASE)
    candidate = prepare_release()
    verify_candidate(candidate)
    stop()
    try:
        start(candidate)
    except Exception:
        stop()
        if previous:
            start(previous)
        raise
    write(RELEASE, candidate)

def status():
    result = {name: healthy(url) for name, url in [('frontend', 'http://127.0.0.1:3000'), ('server', 'http://127.0.0.1:8080/actuator/health')]}
    result['databasePort'] = listening(55432)
    result['release'] = read(RELEASE).get('createdAt')
    print(json.dumps(result, indent=2))
    return all(result[k] for k in ['frontend', 'server', 'databasePort'])

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('action', choices=['init', 'deploy', 'up', 'stop', 'down', 'status', 'check'])
    args = parser.parse_args()
    init()
    with (LOCAL / 'stack.lock').open('w') as lock:
        fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        if args.action == 'deploy':
            deploy()
        elif args.action == 'up':
            release = read(RELEASE)
            if not release:
                raise RuntimeError('Run deploy first.')
            start(release)
        elif args.action in ['stop', 'down']:
            stop()
            if args.action == 'down':
                compose('stop')
        elif args.action in ['status', 'check']:
            good = status()
            if args.action == 'check':
                if not good:
                    raise SystemExit(1)
                subprocess.run(['./node_modules/.bin/playwright', 'test', '--config', 'playwright.live.config.ts'], cwd=ROOT / 'frontend', check=True)
        else:
            print('Initialized .local/stack.env. Existing credentials were preserved.')

if __name__ == '__main__':
    main()
