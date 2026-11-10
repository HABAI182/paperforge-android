#!/usr/bin/env python3
"""Replay published GitHub bytes through the actual Kotlin updater, including an old tag.

No APK is built. --fixtures contains releases.json plus release-manifest.json files
in any subdirectories, downloaded without edits from the releases being checked.
The tests deliberately serve an older release list/manifest to reproduce CDN lag.
Requires the project's cached Kotlin compiler, Android SDK, and an org.json JAR.
"""

import argparse
import copy
import hashlib
import http.server
import json
import pathlib
import subprocess
import threading
import time
import urllib.parse


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--fixtures", type=pathlib.Path, required=True)
    parser.add_argument("--json-jar", type=pathlib.Path, required=True)
    parser.add_argument("--legacy-ref", default="v2.0.0")
    parser.add_argument("--output", type=pathlib.Path, required=True)
    args = parser.parse_args()
    repo = pathlib.Path(__file__).resolve().parents[1]
    toolchain = repo.parent.parent / "work/toolchain"
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=True)
    fixtures = args.fixtures.resolve()
    releases_bytes = (fixtures / "releases.json").read_bytes()
    releases = json.loads(releases_bytes.decode("utf-8-sig"))
    manifests = {}
    for path in fixtures.rglob("release-manifest.json"):
        raw = path.read_bytes()
        data = json.loads(raw.decode("utf-8-sig"))
        manifests[data["versionName"]] = (raw, data)
    new_bytes, new = manifests["2.0.1"]
    old_bytes, old = manifests["2.0.0"]
    assert new["versionCode"] > old["versionCode"]
    new_artifact = new["artifacts"][0]
    newest_release = next(r for r in releases if r["tag_name"] == "v2.0.1")
    published_apk = next(a for a in newest_release["assets"] if a["name"] == new_artifact["file"])
    assert new_artifact["bytes"] == published_apk["size"]
    assert "sha256:" + new_artifact["sha256"] == published_apk["digest"]
    assets = {}
    for release in releases:
        for asset in release["assets"]:
            if asset["name"].endswith("release-manifest.json"):
                assets[asset["id"]] = manifests[release["tag_name"].removeprefix("v")][0]

    state = {"scenario": "fresh", "requests": []}
    older_releases = json.dumps([r for r in releases if r["tag_name"] != "v2.0.1"]).encode()

    class Handler(http.server.BaseHTTPRequestHandler):
        def log_message(self, *_):
            pass

        def do_GET(self):
            state["requests"].append(dict(self.headers))
            path = urllib.parse.urlsplit(self.path).path
            scenario = state["scenario"]
            body = None
            if "/releases?" in self.path:
                if scenario not in ("mirror", "older-candidate", "wrong-distribution", "development", "bad-hash"):
                    body = older_releases if scenario == "stale-api" else releases_bytes
            elif "/releases/assets/" in path:
                body = assets.get(int(path.rsplit("/", 1)[1]))
            elif path.endswith("/release-manifest.json"):
                if scenario != "unavailable-latest":
                    body = old_bytes if scenario in ("mirror", "older-candidate") and not path.startswith("/mirror/") else new_bytes
                    if scenario == "current-slow-mirror" and path.startswith("/mirror/"):
                        time.sleep(5)
                    if scenario in ("wrong-distribution", "development", "bad-hash"):
                        data = copy.deepcopy(new)
                        if scenario == "wrong-distribution":
                            data["distribution"] = "lite"
                        elif scenario == "development":
                            data["channel"] = "development"
                        else:
                            data["artifacts"][0]["sha256"] = "invalid"
                        body = json.dumps(data).encode()
            self.send_response(200 if body is not None else 503)
            self.send_header("Content-Length", str(len(body or b"")))
            self.end_headers()
            try:
                self.wfile.write(body or b"")
            except (BrokenPipeError, ConnectionResetError, ConnectionAbortedError):
                pass  # The short metadata deadline deliberately abandons the slow mirror.

    server = http.server.ThreadingHTTPServer(("127.0.0.1", 0), Handler)
    threading.Thread(target=server.serve_forever, daemon=True).start()
    root = f"http://127.0.0.1:{server.server_port}"
    source_path = "network-engine/src/main/java/cn/paperforge/network/GitHubUpdates.kt"
    cache = toolchain / "gradle-home/caches/modules-2/files-2.1"

    def jar(group, name, version):
        return next((cache / group / name / version).rglob("*.jar")).resolve()

    stdlib = jar("org.jetbrains.kotlin", "kotlin-stdlib", "2.1.20")
    compiler = [jar("org.jetbrains.kotlin", "kotlin-compiler-embeddable", "2.1.20"), stdlib,
                jar("org.jetbrains.kotlin", "kotlin-script-runtime", "2.1.20"),
                jar("org.jetbrains.kotlin", "kotlin-reflect", "2.1.20"),
                jar("org.jetbrains.intellij.deps", "trove4j", "1.0.20200330"),
                jar("org.jetbrains", "annotations", "13.0"),
                jar("org.jetbrains.kotlinx", "kotlinx-coroutines-core-jvm", "1.8.0")]
    dependencies = [stdlib, jar("org.jetbrains.kotlinx", "kotlinx-coroutines-core-jvm", "1.10.2"),
                    args.json_jar.resolve(), toolchain / "android-sdk/platforms/android-36/android.jar"]
    classpath = lambda paths: ";".join(str(p) for p in paths)
    stub = output / "NetworkFiles.kt"
    stub.write_text("package cn.paperforge.network\nobject NetworkFiles { fun integrated(c: android.content.Context) = true }\n")
    runner = output / "Replay.kt"
    runner.write_text('''import cn.paperforge.network.*
import kotlinx.coroutines.runBlocking
import org.json.*
fun main(args: Array<String>) = runBlocking {
    val root = args[0]
    val installed = mapOf("cn.paperforge.app" to InstalledPackage(args[1].toLong(), args[2]))
    val client = GitHubUpdates("", root, integrated=true, webRoot=root, mirrors=listOf(root+"/mirror/"))
    val result = client.check("HABAI182/paperforge-android", "stable", installed, listOf(args[3]))
    println(JSONObject().put("candidates", JSONArray(result.candidates.map { it.json() }))
        .put("releases",result.releases).put("warnings",JSONArray(result.warnings)).toString())
}
''')
    results = []
    try:
        for label, source in [("legacy", subprocess.check_output(["git", "show", f"{args.legacy_ref}:{source_path}"], cwd=repo)),
                              ("fixed", (repo / source_path).read_bytes())]:
            kotlin = output / f"GitHubUpdates-{label}.kt"
            kotlin.write_bytes(source)
            compiled = output / f"{label}.jar"
            subprocess.run(["java", "-cp", classpath(compiler), "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler",
                            "-no-stdlib", "-no-reflect", "-jvm-target", "17", "-classpath", classpath(dependencies),
                            "-d", str(compiled), str(kotlin), str(stub), str(runner)], check=True)
            for scenario in ("fresh", "stale-api", "mirror", "older-candidate", "wrong-distribution", "development", "bad-hash", "current", "current-slow-mirror", "wrong-abi", "unavailable-latest"):
                state["scenario"] = scenario
                state["requests"] = []
                current = scenario in ("current", "current-slow-mirror", "unavailable-latest")
                code = old["versionCode"] - 1 if scenario == "older-candidate" else old["versionCode"]
                started = time.monotonic()
                run = subprocess.run(["java", "-Dfile.encoding=UTF-8", "-cp", classpath([compiled] + dependencies), "ReplayKt", root,
                                      str(new["versionCode"] if current else code),
                                      new["versionName"] if current else old["versionName"],
                                      "x86_64" if scenario == "wrong-abi" else "arm64-v8a"],
                                     capture_output=True, text=True, encoding="utf-8", check=True, timeout=30)
                result = json.loads(run.stdout.strip())
                elapsed = time.monotonic() - started
                candidates, warnings = result["candidates"], result["warnings"]
                codes = [c["versionCode"] for c in candidates]
                if scenario == "fresh" or label == "fixed" and scenario in ("stale-api", "mirror", "older-candidate"):
                    assert codes == [new["versionCode"]], (label, scenario, result)
                    candidate = candidates[0]
                    assert candidate["sha256"] == new_artifact["sha256"]
                    assert candidate["bytes"] == new_artifact["bytes"]
                    assert candidate["fileName"] == new_artifact["file"]
                    if label == "fixed" and scenario != "fresh":
                        assert "/releases/download/v2.0.1/" in candidate["assetUrl"]
                        assert all("/releases/download/v2.0.1/" in u for u in candidate["mirrors"])
                elif label == "legacy" and scenario in ("stale-api", "mirror", "wrong-distribution"):
                    assert not candidates, (label, scenario, result)
                elif label == "legacy" and scenario == "development":
                    assert codes == [new["versionCode"]], (label, scenario, result)
                elif label == "legacy" and scenario == "older-candidate":
                    assert codes == [old["versionCode"]], (label, scenario, result)
                else:
                    assert not candidates, (label, scenario, result)
                    if label == "fixed" and scenario != "current":
                        assert warnings, (label, scenario, result)
                if label == "fixed" and scenario == "current-slow-mirror":
                    assert elapsed < 3.5, ("slow mirror held up the whole check", elapsed)
                if label == "fixed":
                    assert all(r.get("Cache-Control") == "no-cache, max-age=0" for r in state["requests"])
                results.append({"source": label, "sourceSha256": hashlib.sha256(source).hexdigest(),
                                "scenario": scenario, "codes": codes, "warnings": warnings,
                                "requests": len(state["requests"]), "seconds": round(elapsed, 3)})
                print(f"PASS {label:6} {scenario:20} offered={codes} warnings={len(warnings)} seconds={elapsed:.2f}")
    finally:
        server.shutdown()
    (output / "results.json").write_text(json.dumps(results, ensure_ascii=False, indent=2), encoding="utf-8")


if __name__ == "__main__":
    main()
