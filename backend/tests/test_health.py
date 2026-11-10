"""Health check unit tests compatible with both pytest and unittest."""

import unittest

class TestHealth(unittest.TestCase):

    def test_basic_health_assertion(self):
        """Verify testing harness is operational in Stage 1."""
        self.assertEqual(1 + 1, 2)
        service_name = "SafeBank AI"
        self.assertIn("SafeBank", service_name)

    def test_security_pillars_defined(self):
        """Verify that the 3 security pillars are acknowledged."""
        pillars = ["Identity", "Device", "Freedom to Act"]
        self.assertEqual(len(pillars), 3)
        self.assertIn("Freedom to Act", pillars)

if __name__ == "__main__":
    unittest.main()
