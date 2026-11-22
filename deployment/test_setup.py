import tempfile
import unittest
from pathlib import Path
from setup_local import NAMES, complete_env, create_env


class Setup(unittest.TestCase):
    def test_secrets_are_private_unique_and_never_overwritten(self):
        with tempfile.TemporaryDirectory() as directory:
            p = Path(directory) / ".env"; create_env(p); original = p.read_text()
            self.assertEqual(p.stat().st_mode & 0o777, 0o600)
            values = [line.split("=", 1)[1] for line in original.splitlines()]
            self.assertEqual(len(values), len(set(values)))
            self.assertEqual(len(values), len(NAMES))
            with self.assertRaises(FileExistsError): create_env(p)
            self.assertEqual(complete_env(p), [])
            self.assertEqual(p.read_text(), original)

    def test_upgrade_appends_only_missing_values(self):
        with tempfile.TemporaryDirectory() as directory:
            p = Path(directory) / ".env"; p.write_text("MYSQL_ROOT_PASSWORD=keep\n")
            added = complete_env(p)
            self.assertNotIn("MYSQL_ROOT_PASSWORD", added)
            self.assertIn("IPETS_JWT_PRIVATE_KEY", added)
            self.assertTrue(p.read_text().startswith("MYSQL_ROOT_PASSWORD=keep\n"))


if __name__ == '__main__': unittest.main()
