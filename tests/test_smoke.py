import unittest

import discurve


class SmokeTest(unittest.TestCase):
    def test_package_imports(self) -> None:
        self.assertEqual(discurve.__version__, "0.1.0")


if __name__ == "__main__":
    unittest.main()
