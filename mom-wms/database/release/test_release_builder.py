"""验证发布范围与事务边界；真实SQL行为由隔离PG演练负责。"""

import importlib.util
import pathlib
import json
import tempfile
import unittest
from unittest import mock


class ReleaseBuilderTest(unittest.TestCase):
    def module(self):
        path = pathlib.Path(__file__).with_name("build_release.py")
        spec = importlib.util.spec_from_file_location("wms_release", path)
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        return module

    def test_dollar_bodies_and_quoted_transaction_words_stay_in_one_statement(self):
        module = self.module()
        source = "-- BEGIN;\nBEGIN; DO $x$ BEGIN RAISE NOTICE 'COMMIT;'; END $x$; COMMIT; -- end"
        statements = module.statements(source)
        self.assertEqual(len(statements), 3)
        self.assertIn("RAISE NOTICE 'COMMIT;'", statements[1])

    def test_unterminated_body_cannot_produce_a_release(self):
        module = self.module()
        with self.assertRaisesRegex(ValueError, "未结束"):
            module.statements("BEGIN; DO $x$ BEGIN PERFORM 1; COMMIT;")

    def test_unexpected_transaction_control_cannot_escape_atomic_release(self):
        module = self.module()
        with self.assertRaisesRegex(ValueError, "事务"):
            module.body("BEGIN; SELECT 1; COMMIT; BEGIN; SELECT 2; COMMIT;")

    def test_fixed_sources_exclude_other_cumulative_sections(self):
        module = self.module()
        units = module.collect()
        self.assertEqual([unit["id"] for unit in units], [
            "356", "359", "scope", "template", "schedule", "approval",
            "allocation_cancel", "transfer_cancel", "organization",
        ])
        self.assertEqual(len(units), 9)
        for unit in units:
            self.assertNotRegex(unit["body"], r"\bmom_(?:mes|qms)\.")
        self.assertIn("assert_count_scope_open_359", units[1]["body"])
        self.assertNotIn("source_scope_composition_v1:360", units[1]["body"])

    def test_changed_package_cannot_overwrite_previous_publication(self):
        module = self.module()
        with tempfile.TemporaryDirectory() as temporary:
            target = pathlib.Path(temporary) / "package"
            module.build(target, draft=True)
            before = {path.name: path.read_bytes() for path in target.iterdir()}
            changed = module.collect()
            changed[0]["body"] += "SELECT 1;\n"
            with mock.patch.object(module, "collect", return_value=changed):
                with self.assertRaisesRegex(ValueError, "全新目录"):
                    module.build(target, draft=True)
            self.assertEqual(before, {path.name: path.read_bytes() for path in target.iterdir()})

    def test_publication_failure_leaves_no_partial_destination(self):
        module = self.module()
        with tempfile.TemporaryDirectory() as temporary:
            target = pathlib.Path(temporary) / "package"
            with mock.patch.object(pathlib.Path, "rename", side_effect=OSError("injected publication failure")):
                with self.assertRaisesRegex(OSError, "injected publication failure"):
                    module.build(target, draft=True)
            self.assertFalse(target.exists())

    def test_unverified_contract_cannot_create_ready_package(self):
        module = self.module()
        contract = json.loads((pathlib.Path(__file__).parent / "schema-contract.json").read_text())
        contract["sourceHashes"] = {unit["id"]: unit["sha256"] for unit in module.collect()}
        contract["verification"] = "CANDIDATE"
        with tempfile.TemporaryDirectory() as temporary:
            folder = pathlib.Path(temporary)
            path = folder / "schema-contract.json"
            path.write_text(json.dumps(contract))
            with self.assertRaisesRegex(ValueError, "完成验证"):
                module.build(folder / "package", contract_path=path)
            self.assertFalse((folder / "package").exists())

    def test_source_change_cannot_reuse_a_verified_contract(self):
        module = self.module()
        contract = json.loads((pathlib.Path(__file__).parent / "schema-contract.json").read_text())
        contract.update(verification="VERIFIED", verificationInputs=module.verification_inputs())
        contract["sourceHashes"]["359"] = "changed"
        with tempfile.TemporaryDirectory() as temporary:
            folder = pathlib.Path(temporary)
            path = folder / "contract.json"
            path.write_text(json.dumps(contract))
            with self.assertRaisesRegex(ValueError, "来源已变化"):
                module.build(folder / "package", contract_path=path)
            self.assertFalse((folder / "package").exists())


if __name__ == "__main__":
    unittest.main()
