"""失败注入演练；需本机临时PG权限，单独运行，不属于纯构建器测试。"""

import importlib.util
import json
import pathlib
import tempfile
import unittest
from unittest import mock

HERE = pathlib.Path(__file__).resolve().parent
ROOT = HERE.parents[5]


class ReleaseRunnerFailureTest(unittest.TestCase):
    def test_failed_final_probe_never_promotes_ready_artifacts(self):
        spec = importlib.util.spec_from_file_location("wms_release_runner", HERE / "run_isolated.py")
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        original_run = module.run
        contract = HERE.parent / "schema-contract.json"
        before = contract.read_bytes()

        def failing_run(command, log, timeout=120):
            if command[-1] == "fresh":
                raise RuntimeError("injected fresh verification failure")
            return original_run(command, log, timeout)

        with tempfile.TemporaryDirectory(prefix="wms-release-failure-") as temporary:
            output = pathlib.Path(temporary)
            with mock.patch.object(module, "run", side_effect=failing_run):
                with self.assertRaisesRegex(RuntimeError, "injected fresh verification failure"):
                    module.main(output, ROOT / "outputs/20261031_wms_production_source_links/pg-runtime.json")
            self.assertFalse((output / "package").exists(), "失败演练不能留下可发布目录")
            self.assertEqual(before, contract.read_bytes(), "失败不能覆盖上次契约")
            reports = list(output.glob("**/verification.json"))
            self.assertTrue(reports)
            report = json.loads(reports[-1].read_text())
            self.assertEqual("FAILED", report["status"])
            self.assertTrue(report["temporaryServerStopped"])


if __name__ == "__main__":
    unittest.main()
