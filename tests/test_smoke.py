import unittest


class SmokeTest(unittest.TestCase):
    def test_import(self) -> None:
        import pnl_engine

        self.assertEqual(pnl_engine.__version__, "0.1.0")


if __name__ == "__main__":
    unittest.main()

