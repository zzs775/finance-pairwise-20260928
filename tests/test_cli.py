import io
import json
import sys
import unittest

from ecl_engine.cli import main


class CliTest(unittest.TestCase):
    @staticmethod
    def _run_capturing(*argv) -> tuple[int, bytes]:
        buffer = io.BytesIO()
        wrapper = io.TextIOWrapper(buffer, encoding="utf-8")
        old_stdout = sys.stdout
        sys.stdout = wrapper
        try:
            code = main(list(argv))
            wrapper.flush()
        finally:
            sys.stdout = old_stdout
            wrapper.detach()
        return code, buffer.getvalue()

    def test_price_outputs_canonical_json(self) -> None:
        code, raw = self._run_capturing("price", "--version", "v1")
        self.assertEqual(code, 0)
        payload = json.loads(raw.decode("utf-8"))
        self.assertEqual(payload["version"], "v1")
        self.assertEqual(len(payload["contracts"]), 6)

    def test_revise_incremental_succeeds(self) -> None:
        code, raw = self._run_capturing("revise", "--new-version", "v2")
        self.assertEqual(code, 0)
        payload = json.loads(raw.decode("utf-8"))
        self.assertEqual(payload["version"], "v2")

    def test_bad_version_exits_nonzero(self) -> None:
        self.assertEqual(main(["price", "--version", "v9"]), 2)


if __name__ == "__main__":
    unittest.main()
