"""Placeholder test; replaced as modules are implemented."""

import unittest


class SkeletonTest(unittest.TestCase):
    def test_version(self) -> None:
        import ecl_engine

        self.assertEqual(ecl_engine.__version__, "0.1.0")


if __name__ == "__main__":
    unittest.main()
