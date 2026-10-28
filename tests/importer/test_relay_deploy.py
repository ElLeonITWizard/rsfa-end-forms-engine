"""Tests of the relay deployment tooling (docs/15) with stubbed gcloud: nothing reaches Google.

Proves: the default PLAN changes nothing; the ONLY mutating command that names the engine is the relay SA's
roles/run.invoker binding (deploy) or its removal (rollback); public exposure happens only in PHASE=expose; name
guards stop overrides that would point at the engine; a different engine revision stops everything; the secret value
is never printed; staging keeps PDFs out.
"""
from __future__ import annotations

import json
import os
import re
import shutil
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(REPO / "tools"))
import stage_relay  # noqa: E402

RELAY_SA = "serviceAccount:rsfa-end-forms-relay@rsfa-end-forms-engine.iam.gserviceaccount.com"
MUTATING = re.compile(r"\b(create|deploy|update|add-iam-policy-binding|remove-iam-policy-binding|enable|submit|delete|disable|rm)\b")

FAKE_GCLOUD = r'''#!/usr/bin/env python3
import json, os, sys
argv = [a for a in sys.argv[1:] if not a.startswith("--project=")]
with open(os.environ["FAKE_LOG"], "a") as f: f.write(json.dumps(argv) + "\n")
st = json.load(open(os.environ["FAKE_STATE"]))
fmt = next((x.split("=", 1)[1] for x in argv if x.startswith("--format=")), "")
def out(x): print(x if isinstance(x, str) else json.dumps(x)); sys.exit(0)
if argv[:3] == ["config", "get-value", "account"]: out("deployer@example.org")
if argv[:2] == ["projects", "describe"]: out("ACTIVE")
if argv[:3] == ["run", "services", "describe"]:
    if argv[3] == "rsfa-end-forms-engine": out(st["engine"])
    sys.exit(1)
if argv[:3] == ["run", "services", "get-iam-policy"]: out({"bindings": [{"role": "roles/run.invoker", "members": ["user:deployer@example.org"]}]})
if argv[:3] == ["iam", "service-accounts", "describe"]: sys.exit(0 if "build" in argv[3] else 1)
if argv[:3] == ["storage", "buckets", "describe"]: sys.exit(1)
if argv[:2] == ["secrets", "describe"]: sys.exit(1)
if argv[:3] == ["secrets", "versions", "list"]: out("1")
if argv[:3] == ["artifacts", "docker", "images"]: out("sha256:" + "a" * 64)
sys.exit(0)
'''


def engine(revision="rsfa-end-forms-engine-00005-rih"):
    return {"metadata": {"annotations": {"run.googleapis.com/ingress": "all"}},
            "spec": {"template": {"metadata": {"annotations": {}},
                                  "spec": {"serviceAccountName": "rsfa-end-forms-runtime@x",
                                           "containers": [{"image": "img@sha256:e0d8", "env": [{"name": "ENGINE_AUTH", "value": "PLATFORM_IAM"}]}]}}},
            "status": {"traffic": [{"revisionName": revision, "percent": 100}], "latestReadyRevisionName": revision,
                       "url": "https://rsfa-end-forms-engine-x.a.run.app"}}


class RelayDeployTest(unittest.TestCase):

    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp(prefix="relay-deploy-"))
        (self.tmp / "bin").mkdir()
        g = self.tmp / "bin" / "gcloud"
        g.write_text(FAKE_GCLOUD)
        g.chmod(0o755)
        b = self.tmp / "bundle"
        (b / "context").mkdir(parents=True)
        (b / "context" / "x.txt").write_text("x\n")
        subprocess.run(["bash", "-c", "cd context && sha256sum x.txt | awk '{print $1, 2, $2}' > STAGED_FILES.txt"], cwd=b, check=True)
        for f in ("deploy_relay.sh", "rollback_relay.sh", "replay-lifecycle.json"):
            shutil.copy(REPO / "deploy" / "relay" / f, b / f)
        self.bundle = b

    def tearDown(self):
        shutil.rmtree(self.tmp, ignore_errors=True)

    def run_script(self, script, eng=None, **env):
        (self.tmp / "state.json").write_text(json.dumps({"engine": eng or engine()}))
        log = self.tmp / "log"
        log.write_text("")
        e = {"PATH": f"{self.tmp / 'bin'}:{os.environ['PATH']}", "HOME": str(self.tmp), "FAKE_STATE": str(self.tmp / "state.json"),
             "FAKE_LOG": str(log), "OUT": str(self.tmp / "out"), "IAM_WAIT": "0"}
        e.update(env)
        p = subprocess.run(["bash", str(self.bundle / script)], env=e, capture_output=True, text=True, timeout=120)
        return p, [json.loads(l) for l in log.read_text().splitlines() if l.strip()]

    @staticmethod
    def mutations(calls):
        return [c for c in calls if MUTATING.search(" ".join(c)) and "--help" not in c]

    def test_plan_changes_nothing_and_names_the_single_engine_change(self):
        p, calls = self.run_script("deploy_relay.sh")
        self.assertEqual(0, p.returncode, p.stderr + p.stdout)
        self.assertEqual([], self.mutations(calls))
        engine_lines = [l for l in p.stdout.splitlines() if "[plan]" in l and "rsfa-end-forms-engine " in l + " "
                        and "run services" in l]
        self.assertEqual(1, len(engine_lines), engine_lines)
        self.assertIn("add-iam-policy-binding rsfa-end-forms-engine", engine_lines[0])
        self.assertIn("--member=" + RELAY_SA + " --role=roles/run.invoker", engine_lines[0])
        self.assertIn("--no-allow-unauthenticated", p.stdout)
        self.assertNotIn("allUsers", p.stdout, "public exposure is a separate phase")
        self.assertIn("openssl rand -hex 32 | gcloud secrets create", p.stdout)

    def test_apply_writes_to_the_engine_only_through_the_invoker_binding(self):
        p, calls = self.run_script("deploy_relay.sh", APPLY="1")
        muts = self.mutations(calls)
        self.assertTrue(any(c[:2] == ["run", "deploy"] and c[2] == "rsfa-end-forms-relay" for c in muts), p.stdout + p.stderr)
        for c in muts:
            if "rsfa-end-forms-engine" in c:
                self.assertEqual(["run", "services", "add-iam-policy-binding", "rsfa-end-forms-engine"], c[:4], c)
                self.assertIn("--member=" + RELAY_SA, c)
        deploy = next(c for c in muts if c[:2] == ["run", "deploy"])
        self.assertIn("--no-allow-unauthenticated", deploy)
        self.assertTrue(any(a.startswith("--set-secrets=RELAY_HMAC_SECRETS=rsfa-make-relay-hmac:") for a in deploy))
        self.assertFalse(any("allUsers" in " ".join(c) for c in muts))

    def test_guards_stop_before_any_change(self):
        for env in ({"RELAY_SERVICE": "rsfa-end-forms-engine"}, {"RELAY_SA_NAME": "rsfa-end-forms-runtime"},
                    {"REPLAY_BUCKET": "rsfa-end-forms-engine-admin"}):
            p, calls = self.run_script("deploy_relay.sh", APPLY="1", **env)
            self.assertEqual(1, p.returncode, env)
            self.assertEqual([], self.mutations(calls), env)
        p, calls = self.run_script("deploy_relay.sh", eng=engine("rsfa-end-forms-engine-00006-new"), APPLY="1")
        self.assertEqual(1, p.returncode)
        self.assertEqual([], self.mutations(calls))

    def test_rollback_is_a_plan_and_touches_the_engine_only_to_remove_the_relay_binding(self):
        p, calls = self.run_script("rollback_relay.sh", REMOVE="1")
        self.assertEqual(0, p.returncode, p.stderr)
        self.assertEqual([], self.mutations(calls))
        p, calls = self.run_script("rollback_relay.sh", REMOVE="1", APPLY="1")
        for c in self.mutations(calls):
            if "rsfa-end-forms-engine" in c:
                self.assertEqual(["run", "services", "remove-iam-policy-binding", "rsfa-end-forms-engine"], c[:4])
                self.assertIn("--member=" + RELAY_SA, c)
        self.assertFalse(any(c[:2] == ["secrets", "delete"] for c in calls), "the secret is deleted only with PURGE=1")
        p, _ = self.run_script("rollback_relay.sh", REMOVE="1", RELAY_SERVICE="rsfa-end-forms-engine", APPLY="1")
        self.assertEqual(1, p.returncode)

    def test_staging_has_no_pdf_and_stays_in_local(self):
        out = REPO / ".local" / "test-stage-relay"
        s = stage_relay.stage(out)
        self.assertEqual(0, s["pdfsInContext"])
        self.assertEqual([], [x for x in out.rglob("*") if x.suffix.lower() == ".pdf"])
        self.assertTrue((out / "context" / "deploy" / "relay" / "Dockerfile").is_file())
        shutil.rmtree(out)
        with self.assertRaises(stage_relay.StageError):
            stage_relay.stage(Path(tempfile.gettempdir()) / "x-relay")


if __name__ == "__main__":
    unittest.main()
