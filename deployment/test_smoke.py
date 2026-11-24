import io
import runpy
import unittest
import urllib.error
from pathlib import Path
from unittest.mock import patch


class Smoke(unittest.TestCase):
    def test_slow_startup_still_checks_all_services_and_rejects_bad_tokens(self):
        attempts = 0
        rejected = []

        def request(url, **kwargs):
            nonlocal attempts
            if isinstance(url, str):
                attempts += 1
                if attempts <= 45:
                    raise urllib.error.HTTPError(url, 502, 'Starting', {}, None)
                return io.BytesIO(b'{"status":"UP"}')
            rejected.append(url.full_url)
            raise urllib.error.HTTPError(url.full_url, 401, 'Unauthorized', {}, None)

        with patch('urllib.request.urlopen', side_effect=request), patch('time.sleep'):
            runpy.run_path(str(Path(__file__).with_name('smoke.py')))
        self.assertEqual(attempts, 49)
        self.assertEqual(len(rejected), 8)


if __name__ == '__main__':
    unittest.main()
