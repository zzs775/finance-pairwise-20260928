import unittest

from xbrlcheck.errors import XBRLCheckError
from xbrlcheck.limits import Limits
from xbrlcheck.xmlsafe import parse_bytes


class XmlSafeTest(unittest.TestCase):
    def test_basic_parse_and_locations(self):
        doc = parse_bytes(b"<xbrl><context id='c1'/><fact>12</fact></xbrl>", "inst.xml")
        self.assertEqual(doc.root.local_name(), "xbrl")
        self.assertEqual(doc.root.line, 1)
        children = doc.root.direct_children()
        self.assertEqual([node.local_name() for node in children], ["context", "fact"])
        self.assertEqual(children[1].text, "12")

    def test_doctype_is_rejected(self):
        with self.assertRaises(XBRLCheckError) as caught:
            parse_bytes(b'<!DOCTYPE x [<!ENTITY a "boom">]><x/>')
        self.assertEqual(caught.exception.code, "E002")

    def test_external_entity_doctype_rejected(self):
        with self.assertRaises(XBRLCheckError) as caught:
            parse_bytes(
                b'<!DOCTYPE x [<!ENTITY ext SYSTEM "file:///etc/passwd">]><x>&ext;</x>'
            )
        self.assertEqual(caught.exception.code, "E002")

    def test_malformed_xml(self):
        with self.assertRaises(XBRLCheckError) as caught:
            parse_bytes(b"<x><y></x>")
        self.assertEqual(caught.exception.code, "E003")
        self.assertGreaterEqual(caught.exception.location.line, 1)

    def test_depth_limit(self):
        data = b"<a>" + b"<b>" * 20 + b"</b>" * 20 + b"</a>"
        with self.assertRaises(XBRLCheckError) as caught:
            parse_bytes(data, limits=Limits(max_depth=10))
        self.assertEqual(caught.exception.code, "E001")

    def test_element_count_limit(self):
        data = b"<root>" + b"<n/>" * 50 + b"</root>"
        with self.assertRaises(XBRLCheckError) as caught:
            parse_bytes(data, limits=Limits(max_elements=10))
        self.assertEqual(caught.exception.code, "E001")

    def test_text_length_limit(self):
        data = b"<root><n>" + b"x" * 500 + b"</n></root>"
        with self.assertRaises(XBRLCheckError) as caught:
            parse_bytes(data, limits=Limits(max_text_length=100))
        self.assertEqual(caught.exception.code, "E001")

    def test_document_size_limit(self):
        with self.assertRaises(XBRLCheckError) as caught:
            parse_bytes(b"<x/>", limits=Limits(max_document_bytes=2))
        self.assertEqual(caught.exception.code, "E001")

    def test_find_all_document_order(self):
        doc = parse_bytes(b"<root><a><m/></a><a><m/></a></root>")
        self.assertEqual(len(doc.root.find_all("m")), 2)


if __name__ == "__main__":
    unittest.main()
