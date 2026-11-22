"""Exercise the real deployment shell flow with fake external commands and isolated paths."""
import json
import os
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path


class Deploy(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)
        source = Path(__file__).with_name('deploy.sh').read_text()
        source = source.replace('/opt/ipets/deployment', str(self.root)).replace('/var/lock/ipets-deploy.lock', str(self.root / 'lock'))
        self.script = self.root / 'deploy.sh'
        self.script.write_text(source)
        self.previous = 'IPETS_AUTH_IMAGE=ghcr.io/fiddeveloper/ipets-api-service/auth:previous\n'
        (self.root / 'images.env').write_text(self.previous)
        binary = self.root / 'bin'
        binary.mkdir()
        commands = {
            'flock': '#!/bin/sh\nexit 0\n',
            'python3': '#!/bin/sh\nexit "${FAIL_CHECKS:-0}"\n',
            'docker': f'#!{sys.executable}\n' + '''import json, os, pathlib, sys
with open('calls', 'a') as f: f.write(json.dumps(sys.argv[1:]) + '\\n')
if 'pull' in sys.argv and os.environ.get('FAIL_PULL'): sys.exit(7)
if sys.argv[1:3] == ['image', 'inspect']:
    print(sys.argv[3].split(':')[0] + '@sha256:' + 'b' * 64)
''',
        }
        for name, text in commands.items():
            p = binary / name
            p.write_text(text)
            p.chmod(0o755)
        self.env = {**os.environ, 'PATH': str(binary) + ':' + os.environ['PATH']}

    def run_deploy(self, service='auth-service', image=None, **environment):
        if image is None:
            image = 'ghcr.io/fiddeveloper/ipets-api-service/auth:' + 'a' * 40
        return subprocess.run(['bash', str(self.script), service, image], cwd=self.root,
                              env={**self.env, **environment}, capture_output=True, text=True)

    def test_rejects_wrong_registry_service_and_nonimmutable_tag(self):
        for image in ['ghcrXio/fiddeveloper/ipets-api-service/auth:' + 'a' * 40,
                      'ghcr.io/fiddeveloper/ipets-api-service/pet:' + 'a' * 40,
                      'ghcr.io/fiddeveloper/ipets-api-service/auth:develop']:
            self.assertEqual(self.run_deploy(image=image).returncode, 2)
            self.assertEqual((self.root / 'images.env').read_text(), self.previous)
        self.assertFalse((self.root / 'calls').exists())

    def test_failed_pull_or_health_check_restores_previous_selection(self):
        for env in ({'FAIL_PULL': '1'}, {'FAIL_CHECKS': '1'}):
            self.assertNotEqual(self.run_deploy(**env).returncode, 0)
            self.assertEqual((self.root / 'images.env').read_text(), self.previous)
        calls = [json.loads(line) for line in (self.root / 'calls').read_text().splitlines()]
        self.assertTrue(any('up' in call for call in calls))

    def test_success_updates_only_selected_image(self):
        self.assertEqual(self.run_deploy().returncode, 0)
        self.assertEqual((self.root / 'images.env').read_text(), 'IPETS_AUTH_IMAGE=ghcr.io/fiddeveloper/ipets-api-service/auth:' + 'a' * 40 + '\n')

    def test_stack_refresh_resolves_all_five_images_to_digests(self):
        result = self.run_deploy(service='stack', image='')
        self.assertEqual(result.returncode, 0, result.stderr)
        selected = (self.root / 'images.env').read_text().splitlines()
        self.assertEqual(len(selected), 5)
        self.assertTrue(all('@sha256:' in line for line in selected))
