"""Run existing integration tests with a synthetic card; no personal medical files needed."""
import sys
import os
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import e2e_api as api

if __name__ == '__main__':
    os.environ.setdefault('IPETS_E2E_DEMO_ENABLED', 'false')
    api.CARD = api.PRINTED_CARD = Path(__file__).with_name('test-card.jpeg')
    suite = unittest.TestSuite()
    for case in (api.Api, api.AccountDeletion):
        for test in unittest.defaultTestLoader.loadTestsFromTestCase(case):
            if test._testMethodName != 'test_scan_handwritten_card_flags_unreadable_dates':
                suite.addTest(test)
    sys.exit(not unittest.TextTestRunner(verbosity=2).run(suite).wasSuccessful())
