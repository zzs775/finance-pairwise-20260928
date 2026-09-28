import unittest

import xbrlcheck
from xbrlcheck import (
    cli,
    errors,
    graph,
    intervals,
    limits,
    model,
    oracle,
    parseinput,
    report,
    validate,
    versioning,
    xmlsafe,
)


class SkeletonTest(unittest.TestCase):
    def test_package_imports(self):
        self.assertTrue(xbrlcheck.__version__)

    def test_modules_exposed(self):
        for module in (
            cli,
            errors,
            graph,
            intervals,
            limits,
            model,
            oracle,
            parseinput,
            report,
            validate,
            versioning,
            xmlsafe,
        ):
            self.assertTrue(hasattr(module, "__name__"))


if __name__ == "__main__":
    unittest.main()
