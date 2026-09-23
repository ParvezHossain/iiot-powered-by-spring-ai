#!/usr/bin/env python3
"""Run the isolated, deterministic interview demo with a 110-second deadline."""

import os
from pathlib import Path
import signal
import subprocess
import sys
import tempfile
import time


def main():
    root = Path(__file__).resolve().parents[1]
    started = time.monotonic()
    # Offline runs make the timing independent of artifact servers. Preparation is explicit.
    command = [str(root / 'mvnw'), '--offline', '-Dskip.frontend=true', '--batch-mode', '--no-transfer-progress',
               '-Dtest=InterviewDemoTests', 'test']
    env = os.environ.copy()
    # Do not inherit connection settings or JVM/Maven overrides into the isolated demo.
    for key in list(env):
        if key.startswith(('SPRING_', 'RAG_', 'AGENT_', 'MCP_', 'SIMULATOR_', 'OLLAMA_', 'MAVEN_', 'SUREFIRE_',
                           'AUTH_', 'INITIAL_ADMIN_', 'ALERTS_', 'ALERT_EMAIL_', 'GMAIL_')):
            env.pop(key)
    for key in ('JAVA_TOOL_OPTIONS', 'JDK_JAVA_OPTIONS', '_JAVA_OPTIONS'):
        env.pop(key, None)
    print('IIoT interview demo — deterministic model and retrieval fixtures', flush=True)
    print('Real HTTP agent, conversation memory, database, detector, and authenticated MCP.\n', flush=True)
    with tempfile.TemporaryDirectory(prefix='iiot-demo-') as directory:
        transcript = Path(directory) / 'transcript.txt'
        command.insert(-1, '-Ddemo.transcript=' + str(transcript))
        with tempfile.TemporaryFile(mode='w+') as log:
            process = None
            try:
                process = subprocess.Popen(command, cwd=root, env=env, stdout=log,
                                           stderr=subprocess.STDOUT, start_new_session=True)
                code = process.wait(timeout=110)
            except (subprocess.TimeoutExpired, KeyboardInterrupt):
                if process is not None:
                    os.killpg(process.pid, signal.SIGKILL)
                    process.wait()
                print('Demo stopped before the two-minute limit; no external services were changed.', file=sys.stderr)
                return 1
            except OSError as error:
                print('Unable to start Maven: ' + str(error), file=sys.stderr)
                return 1
            if code or not transcript.exists():
                log.seek(0)
                details = log.read()
                print('Demo verification failed. Build/test details:\n' + details[-8000:], file=sys.stderr)
                print('One-time preparation: ./mvnw --batch-mode --no-transfer-progress -Dtest=InterviewDemoTests test', file=sys.stderr)
                return 1
            print(transcript.read_text(), end='')
    print(f'PASS — all demo checks completed in {time.monotonic() - started:.1f}s; temporary services stopped.')
    return 0


if __name__ == '__main__':
    sys.exit(main())
