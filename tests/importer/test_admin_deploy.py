"""Tests of the private admin deployment tooling (D-041, docs/14) with stubbed gcloud and curl: nothing reaches Google.

Proves: the default PLAN changes nothing; mutating commands never target the engine service; the engine's revision is
checked first; invalid ADMIN_USERS stop before any change; a project without an organization stops for the console IAP
step; the verification catches a public invoker; the rollback is a plan by default; staging keeps PDFs out and
sanitises the Monday schema.
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
DEPLOY = REPO / "deploy" / "admin" / "deploy_admin.sh"
ROLLBACK = REPO / "deploy" / "admin" / "rollback_admin.sh"
sys.path.insert(0, str(REPO / "tools"))
import stage_admin  # noqa: E402

PROJECT_NUMBER = "123456789012"
IAP_AGENT = f"serviceAccount:service-{PROJECT_NUMBER}@gcp-sa-iap.iam.gserviceaccount.com"
ADMIN_SA = "rsfa-end-forms-admin@rsfa-end-forms-engine.iam.gserviceaccount.com"
MUTATING = re.compile(r"\b(create|deploy|update|update-traffic|add-iam-policy-binding|remove-iam-policy-binding|enable|cp|"
                      r"submit|delete|disable|rm)\b")

FAKE_GCLOUD = r'''#!/usr/bin/env python3
import json, os, sys
st_path = os.environ["FAKE_STATE"]; st = json.load(open(st_path))
argv = [a for a in sys.argv[1:] if not a.startswith("--project=")]
if os.environ.get("CLOUDSDK_CORE_DISABLE_PROMPTS") != "1": argv = ["PROMPTS-ENABLED"] + argv
with open(os.environ["FAKE_LOG"], "a") as f: f.write(json.dumps(argv) + "\n")
def out(x): print(x if isinstance(x, str) else json.dumps(x)); sys.exit(0)
def save(): json.dump(st, open(st_path, "w"))
a = " ".join(argv); fmt = next((x.split("=", 1)[1] for x in argv if x.startswith("--format=")), "")
if argv[:3] == ["config", "get-value", "account"]: out("deployer@example.org")
if argv[:3] == ["run", "deploy", "--help"]: out("  [--[no-]iap] [--ingress=INGRESS]")
if argv[:2] == ["projects", "describe"]:
    out({"value(lifecycleState)": "ACTIVE", "value(projectNumber)": "%s", "value(parent.type)": st["parent"]}.get(fmt, ""))
if argv[:2] == ["auth", "print-access-token"] or argv[:2] == ["auth", "print-identity-token"]: out("token")
if argv[:3] == ["run", "services", "describe"]:
    svc = argv[3]
    if svc == "rsfa-end-forms-engine": out(st["engine"])
    if not st["admin"]: sys.exit(1)
    out(st["adminService"])
if argv[:3] == ["run", "services", "get-iam-policy"]:
    out(st["engineIam"] if argv[3] == "rsfa-end-forms-engine" else st["adminIam"])
if argv[:3] == ["iam", "service-accounts", "describe"]:
    sys.exit(0 if "build" in argv[3] or st["sa"] else 1)
if argv[:3] == ["artifacts", "repositories", "describe"]: sys.exit(0)
if argv[:3] == ["artifacts", "docker", "images"]: out("sha256:" + "a" * 64)
if argv[:3] == ["storage", "buckets", "describe"]:
    if not st["bucket"]: sys.exit(1)
    out({"value(name)": "b", "value(project_number)": "%s"}.get(fmt, st["bucketJson"]))
if argv[:3] == ["storage", "buckets", "get-iam-policy"]: out(st["bucketIam"])
if argv[:2] == ["storage", "cat"]: sys.exit(1)
if argv[:2] == ["projects", "get-iam-policy"]: out({"bindings": [{"role": "roles/owner", "members": ["user:deployer@example.org"]}]})
if argv[:3] == ["iap", "web", "get-iam-policy"]: out(st["iap"])
if argv[:2] == ["logging", "read"]: out([{"textPayload": "admin service: auth=iap users=1 storage=gcs"},
                                        {"textPayload": "admin GET /admin/api/library status=200 user=u-1a2b3c4d"}])
if argv[:2] == ["run", "revisions"]: sys.exit(0)
# anything else changes something: record its effect on the fake project
if argv[:2] == ["run", "deploy"]: st["admin"] = True
if argv[:3] == ["storage", "buckets", "create"]: st["bucket"] = True
if argv[:3] == ["iam", "service-accounts", "create"]: st["sa"] = True
save(); sys.exit(0)
''' % (PROJECT_NUMBER, PROJECT_NUMBER)

FAKE_CURL = r'''#!/usr/bin/env python3
import json, sys
args = sys.argv[1:]
if any("testIamPermissions" in a for a in args):
    body = json.loads(args[args.index("-d") + 1]); print(json.dumps({"permissions": body["permissions"]})); sys.exit(0)
w = args[args.index("-w") + 1] if "-w" in args else ""
if "redirect_url" in w: print("https://accounts.google.com/o/oauth2/v2/auth?client_id=x", end="")
elif "http_code" in w: print("401" if any(a.startswith("Authorization") for a in args) else "302", end="")
'''


def engine(revision="rsfa-end-forms-engine-00005-rih"):
    return {"metadata": {"annotations": {"run.googleapis.com/ingress": "all"}},
            "spec": {"template": {"metadata": {"annotations": {"autoscaling.knative.dev/maxScale": "2"}},
                                  "spec": {"serviceAccountName": "rsfa-end-forms-runtime@x", "containers": [
                                      {"image": "img@sha256:e0d8", "env": [{"name": "ENGINE_AUTH", "value": "PLATFORM_IAM"}]}]}}},
            "status": {"traffic": [{"revisionName": revision, "percent": 100}], "latestReadyRevisionName": revision}}


def admin_service():
    return {"metadata": {"annotations": {"run.googleapis.com/ingress": "all", "run.googleapis.com/iap-enabled": "true"}},
            "spec": {"template": {"metadata": {"annotations": {"autoscaling.knative.dev/maxScale": "2"}},
                                  "spec": {"serviceAccountName": ADMIN_SA, "containers": [{"image": "admin@sha256:a", "env": [
                                      {"name": "ENGINE_ROLE", "value": "admin"}, {"name": "ADMIN_USERS", "value": "a@example.org:admin"},
                                      {"name": "IAP_AUDIENCE", "value": "/projects/1/locations/r/services/rsfa-end-forms-admin"},
                                      {"name": "ADMIN_BUCKET", "value": "rsfa-end-forms-engine-admin"}]}]}}},
            "status": {"url": "https://rsfa-end-forms-admin-xyz.a.run.app", "latestReadyRevisionName": "rsfa-end-forms-admin-00001-abc"}}


def state(**kw):
    s = {"parent": "", "admin": False, "bucket": False, "sa": False, "engine": engine(),
         "engineIam": {"bindings": [{"role": "roles/run.invoker", "members": ["user:deployer@example.org"]}]},
         "adminService": admin_service(), "adminIam": {"bindings": [{"role": "roles/run.invoker", "members": [IAP_AGENT]}]},
         "iap": {"bindings": [{"role": "roles/iap.httpsResourceAccessor", "members": ["user:a@example.org"]}]},
         "bucketJson": {"public_access_prevention": "enforced", "uniform_bucket_level_access": True, "versioning_enabled": True},
         "bucketIam": {"bindings": [
             {"role": "roles/storage.legacyBucketOwner", "members": ["projectOwner:rsfa-end-forms-engine"]},
             {"role": "roles/storage.objectCreator", "members": ["serviceAccount:" + ADMIN_SA]},
             {"role": "roles/storage.objectViewer", "members": ["serviceAccount:" + ADMIN_SA]}]}}
    s.update(kw)
    return s


class AdminDeployScriptTest(unittest.TestCase):

    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp(prefix="admin-deploy-"))
        self.bin = self.tmp / "bin"
        self.bin.mkdir()
        for name, body in (("gcloud", FAKE_GCLOUD), ("curl", FAKE_CURL)):
            p = self.bin / name
            p.write_text(body)
            p.chmod(0o755)
        bundle = self.tmp / "bundle"
        (bundle / "context").mkdir(parents=True)
        (bundle / "context" / "Dockerfile.txt").write_text("x\n")
        os.utime(bundle / "context" / "Dockerfile.txt", (1.7e9, 1.7e9))
        subprocess.run(["bash", "-c", "cd context && sha256sum Dockerfile.txt | awk '{print $1, 2, $2}' > STAGED_FILES.txt"],
                       cwd=bundle, check=True)
        (bundle / "config").mkdir()
        (bundle / "config" / "monday-schema.json").write_text(json.dumps({"board": {"columns": [{"id": "a", "title": "A", "type": "text"}]},
                                                                          "sanitised": {"columns": 1}}))
        for f in ("deploy_admin.sh", "rollback_admin.sh", "bucket-lifecycle.json"):
            shutil.copy(REPO / "deploy" / "admin" / f, bundle / f)
        self.bundle = bundle

    def tearDown(self):
        shutil.rmtree(self.tmp, ignore_errors=True)

    def run_script(self, script, st, **env):
        (self.tmp / "state.json").write_text(json.dumps(st))
        log = self.tmp / "gcloud.log"
        log.write_text("")
        e = {"PATH": f"{self.bin}:{os.environ['PATH']}", "HOME": str(self.tmp), "FAKE_STATE": str(self.tmp / "state.json"),
             "FAKE_LOG": str(log), "OUT": str(self.tmp / "out"), "IAM_WAIT": "0"}
        e.update(env)
        p = subprocess.run(["bash", str(self.bundle / script)], env=e, capture_output=True, text=True, timeout=120)
        calls = [json.loads(l) for l in log.read_text().splitlines() if l.strip()]
        return p, calls

    @staticmethod
    def mutations(calls):
        return [c for c in calls if MUTATING.search(" ".join(c)) and "--help" not in c]

    def test_plan_is_the_default_and_changes_nothing(self):
        p, calls = self.run_script("deploy_admin.sh", state(), ADMIN_USERS="a@example.org:admin")
        self.assertEqual(0, p.returncode, p.stderr + p.stdout)
        self.assertEqual([], self.mutations(calls), "a plan only reads")
        self.assertIn("[plan] gcloud --project=rsfa-end-forms-engine services enable iap.googleapis.com", p.stdout)
        self.assertIn("[plan] gcloud --project=rsfa-end-forms-engine run deploy rsfa-end-forms-admin", p.stdout)
        self.assertIn("--no-iap", p.stdout, "no organization: IAP is first enabled in the console")
        self.assertNotIn("a@example.org", (self.tmp / "out" / "admin-deployment-record.json").read_text())

    def test_apply_creates_only_admin_resources_and_never_targets_the_engine(self):
        p, calls = self.run_script("deploy_admin.sh", state(), ADMIN_USERS="a@example.org:admin,b@example.org:author", APPLY="1")
        self.assertEqual(0, p.returncode, p.stderr + p.stdout)
        self.assertIn("ONE-TIME CONSOLE STEP", p.stdout)
        muts = self.mutations(calls)
        self.assertTrue(any(c[:2] == ["run", "deploy"] and c[2] == "rsfa-end-forms-admin" for c in muts))
        self.assertTrue(any(c[:3] == ["storage", "buckets", "create"] and "--public-access-prevention" in c for c in muts))
        for c in muts:
            self.assertNotIn("rsfa-end-forms-engine ", " ".join(c) + " ", f"mutating command names the engine: {c}")
            self.assertFalse(any(x in ("allUsers", "allAuthenticatedUsers") or x.endswith("allUsers") for x in c), c)
            self.assertNotIn("--allow-unauthenticated", c)
        deploy = next(c for c in muts if c[:2] == ["run", "deploy"])
        for flag in ("--no-allow-unauthenticated", "--no-iap", "--max-instances=2", f"--service-account={ADMIN_SA}"):
            self.assertIn(flag, deploy)
        env = (self.tmp / "out" / "admin-env.yaml").read_text()
        self.assertIn(f'IAP_AUDIENCE: "/projects/{PROJECT_NUMBER}/locations/australia-southeast1/services/rsfa-end-forms-admin"', env)
        self.assertEqual(0o600, (self.tmp / "out" / "admin-env.yaml").stat().st_mode & 0o777)

    def test_access_phase_then_verification_passes(self):
        st = state(admin=True, bucket=True, sa=True)
        p, calls = self.run_script("deploy_admin.sh", st, ADMIN_USERS="a@example.org:admin", APPLY="1", PHASE="access")
        self.assertEqual(0, p.returncode, p.stderr + p.stdout)
        muts = self.mutations(calls)
        self.assertIn(["run", "services", "add-iam-policy-binding", "rsfa-end-forms-admin", "--region=australia-southeast1",
                       f"--member={IAP_AGENT}", "--role=roles/run.invoker"], muts)
        self.assertTrue(any(c[:3] == ["iap", "web", "add-iam-policy-binding"] and "--member=user:a@example.org" in c
                            and "--resource-type=cloud-run" in c for c in muts))
        self.assertIn("all automated checks passed", p.stdout)
        self.assertIn("engine rsfa-end-forms-engine unchanged", p.stdout)

    def test_verification_fails_on_a_public_invoker(self):
        st = state(admin=True, bucket=True, sa=True,
                   adminIam={"bindings": [{"role": "roles/run.invoker", "members": [IAP_AGENT, "allUsers"]}]})
        p, _ = self.run_script("deploy_admin.sh", st, PHASE="verify")
        self.assertEqual(1, p.returncode)
        self.assertIn("[FAIL] admin invokers = exactly the IAP service agent", p.stdout)

    def test_wrong_engine_revision_or_bad_users_stop_before_any_change(self):
        p, calls = self.run_script("deploy_admin.sh", state(engine=engine("rsfa-end-forms-engine-00006-new")),
                                   ADMIN_USERS="a@example.org:admin", APPLY="1")
        self.assertEqual(1, p.returncode)
        self.assertIn("not rsfa-end-forms-engine-00005-rih", p.stderr)
        self.assertEqual([], self.mutations(calls))
        for users in ("a@example.org:author", "a@example.org:owner", "not-an-email:admin", "a@example.org:admin,A@example.org:viewer"):
            p, calls = self.run_script("deploy_admin.sh", state(), ADMIN_USERS=users, APPLY="1")
            self.assertEqual(1, p.returncode, users)
            self.assertEqual([], self.mutations(calls), users)
        p, calls = self.run_script("deploy_admin.sh", state(), APPLY="1")
        self.assertEqual(1, p.returncode, "ADMIN_USERS is required")
        self.assertEqual([], self.mutations(calls))

    def test_rollback_is_a_plan_by_default_and_keeps_data(self):
        p, calls = self.run_script("rollback_admin.sh", state(admin=True, bucket=True, sa=True), REMOVE="1")
        self.assertEqual(0, p.returncode, p.stderr + p.stdout)
        self.assertEqual([], self.mutations(calls))
        self.assertIn("[plan] gcloud --project=rsfa-end-forms-engine run services delete rsfa-end-forms-admin", p.stdout)
        self.assertIn("kept", p.stdout)
        p, calls = self.run_script("rollback_admin.sh", state(admin=True, bucket=True, sa=True), REMOVE="1", APPLY="1")
        self.assertEqual(0, p.returncode, p.stderr + p.stdout)
        self.assertFalse(any(c[:2] == ["storage", "rm"] for c in calls), "data is deleted only with DELETE_DATA=1")
        self.assertTrue(all("rsfa-end-forms-engine" not in c[3:4] for c in self.mutations(calls)))

    def test_admin_names_can_never_point_at_the_engine(self):
        for env in ({"ADMIN_SERVICE": "rsfa-end-forms-engine"}, {"ADMIN_BUCKET": "rsfa-end-forms-engine-build-src"},
                    {"ADMIN_SA_NAME": "rsfa-end-forms-runtime"}):
            p, calls = self.run_script("deploy_admin.sh", state(), ADMIN_USERS="a@example.org:admin", APPLY="1", **env)
            self.assertEqual(1, p.returncode, env)
            self.assertEqual([], self.mutations(calls), env)
        p, calls = self.run_script("rollback_admin.sh", state(admin=True, bucket=True, sa=True), REMOVE="1", APPLY="1",
                                   ADMIN_SERVICE="rsfa-end-forms-engine")
        self.assertEqual(1, p.returncode)
        self.assertEqual([], self.mutations(calls), "a rollback can never delete the engine")

    def test_prompts_are_disabled_and_the_identity_is_matched(self):
        p, calls = self.run_script("deploy_admin.sh", state(), ADMIN_USERS="Deployer@example.org:admin")
        self.assertEqual(0, p.returncode, p.stderr + p.stdout)
        self.assertFalse(any(c and c[0] == "PROMPTS-ENABLED" for c in calls), "every gcloud call runs with prompts disabled")
        self.assertIn("the authenticated Google identity deployer@example.org is in ADMIN_USERS", p.stdout)
        p, _ = self.run_script("deploy_admin.sh", state(), ADMIN_USERS="someone.else@example.org:admin")
        self.assertIn("[warn] deployer@example.org is not in ADMIN_USERS", p.stdout)

    def test_lifecycle_never_deletes_drafts_approvals_or_audit(self):
        rules = json.loads((REPO / "deploy" / "admin" / "bucket-lifecycle.json").read_text())["rule"]
        self.assertTrue(rules)
        for r in rules:
            self.assertEqual(["results/"], r["condition"].get("matchesPrefix"), r)
        bad = state(admin=True, bucket=True, sa=True)
        bad["bucketJson"] = dict(bad["bucketJson"], lifecycle_config={"rule": [
            {"action": {"type": "Delete"}, "condition": {"daysSinceNoncurrentTime": 90}}]})
        p, _ = self.run_script("deploy_admin.sh", bad, PHASE="verify")
        self.assertEqual(1, p.returncode)
        self.assertIn("[FAIL] lifecycle deletes only synthetic test outputs", p.stdout)

    def test_scripts_never_write_to_the_engine_service(self):
        for script in (DEPLOY, ROLLBACK):
            for line in script.read_text().splitlines():
                if re.search(r"^\s*(run|gc)\b|\$ ", line) and "ENGINE_SERVICE" in line:
                    self.assertRegex(line, r"(describe|get-iam-policy)", f"{script.name}: {line.strip()}")


class StageAdminTest(unittest.TestCase):

    def test_schema_is_reduced_to_columns_and_labels(self):
        raw = {"board": {"owners": [{"email": "owner@example.org"}], "views": [1], "columns": [
            {"id": "text1", "title": "Applicant 1 Full Name", "type": "text", "description": "d", "settings": {}},
            {"id": "dd1", "title": "Lender", "type": "dropdown", "settings": {"labels": [
                {"id": 1, "label": "ANZ"}, {"id": 2, "label": "Old", "is_deactivated": True}]}}]}}
        s = stage_admin.sanitise_schema(raw)
        self.assertEqual([{"id": "text1", "title": "Applicant 1 Full Name", "type": "text"},
                          {"id": "dd1", "title": "Lender", "type": "dropdown", "settings": {"labels": [{"label": "ANZ"}]}}],
                         s["board"]["columns"])
        self.assertNotIn("owner@example.org", json.dumps(s))

    def test_schema_with_contact_details_is_refused(self):
        raw = {"board": {"columns": [{"id": "dd", "title": "Adviser", "type": "dropdown",
                                      "settings": {"labels": [{"label": "someone@example.org"}]}}]}}
        with self.assertRaises(stage_admin.StageError):
            stage_admin.sanitise_schema(raw)

    def test_context_holds_no_pdf_and_outputs_stay_in_local(self):
        tmp = Path(tempfile.mkdtemp(prefix="sch-"))
        try:
            schema = tmp / "s.json"
            schema.write_text(json.dumps({"board": {"columns": [{"id": "a", "title": "A", "type": "text"}]}}))
            with self.assertRaises(stage_admin.StageError):
                stage_admin.stage(schema, tmp / "out")
            out = REPO / ".local" / "test-stage-admin"
            summary = stage_admin.stage(schema, out)
            self.assertEqual(0, summary["pdfsInContext"])
            self.assertEqual([], [p for p in (out / "context").rglob("*") if p.suffix.lower() == ".pdf"])
            self.assertTrue((out / "context" / "deploy" / "admin" / "Dockerfile").is_file())
            self.assertTrue((out / "context" / "engine" / "src" / "main" / "resources" / "ui" / "admin" / "admin.js").is_file())
            self.assertFalse((out / "context" / "config").exists(), "the schema is configuration, not image content")
            shutil.rmtree(out)
        finally:
            shutil.rmtree(tmp, ignore_errors=True)


if __name__ == "__main__":
    unittest.main()
