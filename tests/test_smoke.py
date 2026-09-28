import unittest


class SmokeTest(unittest.TestCase):
    def test_imports(self) -> None:
        import abs_waterfall  # noqa: F401


if __name__ == "__main__":
    unittest.main()
