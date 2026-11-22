import unittest
from pathlib import Path
from gcp.provision import provider_condition


class Config(unittest.TestCase):
    def test_federation_trust_pins_ids_and_develop(self):
        condition = provider_condition('110385422', ['123', '456'])
        self.assertIn("assertion.repository_owner_id == '110385422'", condition)
        self.assertIn('["123", "456"]', condition)
        self.assertIn("assertion.ref == 'refs/heads/develop'", condition)
        for owner, repos in [('name', ['123']), ('1', []), ('1', ['1\' || true'])]:
            with self.assertRaises(ValueError):
                provider_condition(owner, repos)

    def test_cloud_overlay_keeps_api_off_public_interface(self):
        config = Path(__file__).with_name('compose.yaml').read_text()
        self.assertIn('10.48.0.2:18080:80', config)
        self.assertNotIn('0.0.0.0', config)
        self.assertIn("IPETS_DEMO_USER_ENABLED: 'false'", config)
