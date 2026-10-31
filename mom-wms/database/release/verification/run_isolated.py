"""在全新本机PG中演练固定WMS发布包；不读取运行库配置。"""

import argparse
import importlib.util
import json
import pathlib
import shutil
import socket
import subprocess
import tempfile

HERE = pathlib.Path(__file__).resolve().parent
JAVA = pathlib.Path("/Library/Java/JavaVirtualMachines/zulu-21.jdk/Contents/Home/bin")
JDBC = pathlib.Path.home() / ".m2/repository/org/postgresql/postgresql/42.7.7/postgresql-42.7.7.jar"


def run(command, log, timeout=120):
    with log.open("w", encoding="utf-8") as stream:
        result = subprocess.run(command, stdout=stream, stderr=subprocess.STDOUT, timeout=timeout)
    if result.returncode:
        raise RuntimeError(f"命令失败，退出码{result.returncode}；详见{log}")


def save_json(path, value):
    text = json.dumps(value, ensure_ascii=False, indent=2) + "\n"
    with tempfile.NamedTemporaryFile(mode="w", encoding="utf-8", dir=path.parent, delete=False) as stream:
        staged = pathlib.Path(stream.name)
        stream.write(text)
    try:
        staged.replace(path)
    finally:
        staged.unlink(missing_ok=True)


def main(output, metadata):
    spec = importlib.util.spec_from_file_location("wms_release", HERE.parent / "build_release.py")
    builder = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(builder)
    runtime_info = json.loads(metadata.read_text(encoding="utf-8"))
    if runtime_info.get("publishedChecksumVerified") is not True:
        raise ValueError("仅复用已核对公开校验和的PG运行时")
    runtime = pathlib.Path(runtime_info["runtime"]).resolve()
    output.mkdir(parents=True, exist_ok=True)
    output = output.resolve()
    attempt = pathlib.Path(tempfile.mkdtemp(prefix="attempt-", dir=output))
    draft = attempt / "draft"
    manifest = builder.build(draft, draft=True)
    shutil.copyfile(HERE / "baseline.sql", draft / "baseline.sql")
    shutil.copyfile(HERE / "baseline.sql", attempt / "baseline.sql")
    classes = attempt / "classes"
    classes.mkdir()
    temporary = pathlib.Path(tempfile.mkdtemp(prefix="wms-release-pg-20261031-"))
    data = temporary / "data"
    with socket.socket() as listener:
        listener.bind(("127.0.0.1", 0))
        port = listener.getsockname()[1]
    probe = [str(JAVA / "java"), "-cp", f"{classes}:{JDBC}", "WmsReleaseProbe",
             f"jdbc:postgresql://127.0.0.1:{port}/postgres", str(data)]
    summary = {
        "status": "RUNNING", "sharedDatabaseConnected": False,
        "synthesizedBaseline": True, "verifiedPostgres": runtime_info["version"],
        "targetPostgres": "18.3", "targetPostgresCertified": False,
        "dataDirectory": str(data), "loopbackPort": port, "evidenceDirectory": str(attempt),
        "sourceHashes": {unit["id"]: unit["sha256"] for unit in manifest["units"]},
        "verificationInputs": builder.verification_inputs(), "temporaryServerStopped": False,
    }
    started = False
    try:
        run([str(JAVA / "javac"), "-d", str(classes), str(HERE / "WmsReleaseProbe.java")], attempt / "javac.log")
        run([str(runtime / "bin/initdb"), "-D", str(data), "-U", "wms_verify", "-A", "trust",
             "--encoding=UTF8", "--locale=C", "--no-sync"], attempt / "initdb.log")
        # 只监听loopback；禁用Unix socket，避免混用其他本机PG实例。
        run([str(runtime / "bin/pg_ctl"), "-D", str(data), "-l", str(temporary / "server.log"),
             "-w", "-t", "15", "-o", f"-h 127.0.0.1 -p {port} -c unix_socket_directories=", "start"],
            attempt / "start.log", timeout=30)
        started = True
        run(probe + [str(draft), "bootstrap"], attempt / "pg-bootstrap.log")
        objects = json.loads((draft / "schema-objects.json").read_text(encoding="utf-8"))
        contract = {
            "kind": builder.KIND, "verifiedPostgres": runtime_info["version"], "verification": "CANDIDATE",
            "sourceHashes": summary["sourceHashes"], "verificationInputs": summary["verificationInputs"],
            "objects": objects,
        }
        contract_path = attempt / "candidate-contract.json"
        save_json(contract_path, contract)
        candidate = attempt / "candidate"
        candidate_manifest = builder.build(candidate, contract_path=contract_path, candidate=True)
        run(probe + [str(candidate), "verify"], attempt / "pg-package.log")
        run(probe + [str(candidate), "fresh"], attempt / "pg-fresh-package.log")
        checks = []
        for name in ("pg-bootstrap.log", "pg-package.log", "pg-fresh-package.log"):
            content = (attempt / name).read_text(encoding="utf-8")
            if "ALL CHECKS PASSED" not in content:
                raise RuntimeError(f"{name}缺少完成标识")
            checks.extend(line[5:] for line in content.splitlines() if line.startswith("PASS "))
        summary.update(status="VERIFIED_PENDING_SHUTDOWN", checks=checks, passed=len(checks),
                       objectContracts=len(objects), packageFiles=candidate_manifest["files"])
    except Exception as error:
        summary.update(status="FAILED", error=str(error))
        raise
    finally:
        # 即使启动命令中途报错，也只检查本轮新目录，绝不操作正在运行的WMS或其他PG。
        if started or (data / "postmaster.pid").exists():
            try:
                run([str(runtime / "bin/pg_ctl"), "-D", str(data), "-m", "fast", "-w", "-t", "15", "stop"],
                    attempt / "stop.log", timeout=30)
                summary["temporaryServerStopped"] = not (data / "postmaster.pid").exists()
            except Exception as error:
                summary.update(status="FAILED", stopError=str(error))
        if (temporary / "server.log").exists():
            shutil.copyfile(temporary / "server.log", attempt / "pg-server.log")
        save_json(attempt / "verification.json", summary)
    if summary["status"] != "VERIFIED_PENDING_SHUTDOWN" or not summary["temporaryServerStopped"]:
        raise RuntimeError("演练或临时实例停机未通过；检查本次attempt/verification.json")

    # 整包验证及停机均成功，才允许生成READY包和原子替换长期契约。
    try:
        contract["verification"] = "VERIFIED"
        save_json(contract_path, contract)
        final_manifest = builder.build(output / "package", contract_path=contract_path)
        if final_manifest["files"] != candidate_manifest["files"]:
            raise RuntimeError("最终SQL与实际演练候选包不一致")
        save_json(HERE.parent / "schema-contract.json", contract)
        summary["status"] = "PASSED"
        save_json(attempt / "verification.json", summary)
        save_json(output / "verification.json", summary)
    except Exception as error:
        summary.update(status="FAILED", publicationError=str(error))
        save_json(attempt / "verification.json", summary)
        raise
    print(json.dumps({key: summary[key] for key in ("status", "passed", "objectContracts", "temporaryServerStopped")}, ensure_ascii=False))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=pathlib.Path, required=True)
    parser.add_argument("--runtime-metadata", type=pathlib.Path, required=True)
    args = parser.parse_args()
    main(args.output, args.runtime_metadata)
