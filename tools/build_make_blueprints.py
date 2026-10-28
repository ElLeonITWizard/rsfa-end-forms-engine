#!/usr/bin/env python3
"""Generate the importable Make blueprints of the isolated test scenario (iteration 12, docs/15):

  make/e2e-scenario/blueprint.synthetic.json   "RSFA TEST – End Forms → SharePoint (synthetic T1)"   no Monday read
  make/e2e-scenario/blueprint.monday.json      "RSFA TEST – End Forms → SharePoint (approved test item)"

Both are linear and share every module after the input step. The Code module source is embedded VERBATIM from
make/test-scenario/code-module-sign.js (the file the local tests run), so Make executes exactly the tested code.

Placeholders (never real secrets; replaced in the Make editor after import, see docs/15 §4):
  <<RELAY_URL>> <<RELAY_TEST_SECRET>> <<SP_SITE_ID>> <<SP_DRIVE_ID>> <<SP_TEST_FOLDER_PATH>> <<APPROVED_ITEM_IDS>>
  data store id 0 (select "engine-test-runs" in modules 7, 9 and the record modules)
Connections default to the existing RSFA connections seen read-only in scenario 9498962 (Monday 240128,
SharePoint 13527429): confirm in the editor that they are the intended accounts.
Usage: python3 tools/build_make_blueprints.py   (writes both files; deterministic)
"""
from __future__ import annotations

import json
from pathlib import Path

REPO = Path(__file__).resolve().parents[1]
OUT = REPO / "make" / "e2e-scenario"
CODE = (REPO / "make" / "test-scenario" / "code-module-sign.js").read_text(encoding="utf-8")
T1 = json.loads((REPO / "make" / "test-scenario" / "payloads" / "T1.json").read_text(encoding="utf-8"))
MONDAY_CONN = 240128
SHAREPOINT_CONN = 13527429
BOARD = "5026186805"
COLUMNS = ["text_mm2tsfyq", "text_mm2tgjpw", "text_mkzvqaw7", "numeric_mkzvmp91", "numeric_mkzvk0wg", "dropdown_mkzvfgeg"]
FINAL = ["filed", "failed", "failed-after-retries", "upload-failed"]


def cond(a, o, b=None):
    c = {"a": a, "o": o}
    if b is not None:
        c["b"] = b
    return c


def flt(name, *groups):
    """Filter: OR of AND-groups."""
    return {"name": name, "conditions": [list(g) for g in groups]}


def meta(x):
    return {"designer": {"x": x, "y": 0}}


def record(mid, status_expr, extra=None, x=0):
    """datastore:AddRecord (overwrite) — codes, ids and file names only."""
    data = {"status": status_expr, "itemId": "{{4.result.itemId}}", "idempotencyKey": "{{4.result.idempotencyKey}}",
            "correlationId": "{{11.result.correlationId}}", "runId": "{{2.runId}}", "attempt": "{{8.i}}",
            "httpStatus": "{{12.statusCode}}", "code": "{{12.data.code}}", "otherBanks": "{{4.result.otherBanks}}",
            "at": "{{formatDate(now; \"YYYY-MM-DDTHH:mm:ssZ\")}}"}
    data.update(extra or {})
    return {"id": mid, "module": "datastore:AddRecord", "version": 1, "parameters": {"datastore": 0},
            "mapper": {"key": "{{4.result.idempotencyKey}}", "overwrite": True, "data": data}, "metadata": meta(x)}


def code_module(mid, inputs, x):
    return {"id": mid, "module": "code:ExecuteCode", "version": 1, "parameters": {},
            "mapper": {"language": "javascript", "inputFormat": "editor", "codeEditorJavascript": CODE,
                       "input": [{"name": k, "value": v} for k, v in inputs]}, "metadata": meta(x)}


def build(mode: str) -> dict:
    variables = [
        {"name": "today", "value": "{{formatDate(now; \"YYYY-MM-DD\"; \"Pacific/Auckland\")}}"},
        {"name": "runId", "value": "{{uuid}}"},
        {"name": "relayUrl", "value": "<<RELAY_URL>>/v1/masterboard/documents"},
        {"name": "spSite", "value": "<<SP_SITE_ID>>"},
        {"name": "spDrive", "value": "<<SP_DRIVE_ID>>"},
        {"name": "spFolder", "value": "<<SP_TEST_FOLDER_PATH>>"},
    ]
    if mode == "monday":
        variables.append({"name": "approvedItemIds", "value": "<<APPROVED_ITEM_IDS>>"})
        variables.append({"name": "itemId", "value": "{{trim(var.input.itemId)}}"})
    flow = [{"id": 2, "module": "util:SetVariables", "version": 1, "parameters": {},
             "mapper": {"variables": variables, "scope": "roundtrip"}, "metadata": meta(0)}]
    sign_common = [("executionId", "{{2.runId}}"), ("today", "{{2.today}}"), ("secret", "<<RELAY_TEST_SECRET>>")]
    if mode == "monday":
        flow.append({"id": 3, "module": "monday:GetItemV2", "version": 2, "parameters": {"__IMTCONN__": MONDAY_CONN},
                     "mapper": {"id": "{{2.itemId}}", "boardId": BOARD, "showParentItem": False, "showSubitems": False},
                     # F1: only an approved test item is ever read (the Code module re-checks it)
                     "filter": flt("F1 approved test item only",
                                   [cond("{{contains(split(2.approvedItemIds; \",\"); 2.itemId)}}", "boolean:equal", "true")]),
                     "metadata": meta(300)})
        inputs = [("mode", "monday"), ("itemId", "{{2.itemId}}"), ("boardId", "{{3.board.id}}"),
                  ("approvedItemIds", "{{2.approvedItemIds}}")]
        inputs += [(c, "{{3.mappable_column_values.%s.text}}" % c) for c in COLUMNS]
    else:
        inputs = [("payload", json.dumps(T1, ensure_ascii=False, separators=(",", ":")))]
    sign = lambda mid, x: code_module(mid, inputs + sign_common + [("timestamp", "{{formatDate(now; \"X\")}}")], x)
    flow.append(sign(4, 600))
    retry_tail = [
        {"id": 9, "module": "datastore:GetRecord", "version": 1, "parameters": {"datastore": 0},
         "mapper": {"key": "{{4.result.idempotencyKey}}"},
         # stop repeating once this run reached a final state
         "filter": flt("F3 not final for this run",
                       [cond("{{9.runId}}", "text:notequal", "{{2.runId}}")],
                       [cond("{{9.status}}", "text:equal", "retrying")]),
         "metadata": meta(1500)},
        {"id": 10, "module": "util:FunctionSleep", "version": 1, "parameters": {},
         "mapper": {"duration": "{{if(8.i = 1; 1; 60 * (8.i - 1))}}"}, "metadata": meta(1800)},
        sign(11, 2100),
        {"id": 12, "module": "http:MakeRequest", "version": 4, "parameters": {"authenticationType": "noAuth"},
         "mapper": {"url": "{{2.relayUrl}}", "method": "post",
                    "headers": [{"name": "X-RSFA-Timestamp", "value": "{{11.result.timestamp}}"},
                                {"name": "X-RSFA-Signature", "value": "{{11.result.signature}}"}],
                    "contentType": "custom", "contentTypeValue": "application/json", "rawBodyContent": "{{11.result.body}}",
                    "parseResponse": True, "stopOnHttpError": False, "timeout": 120, "allowRedirects": False,
                    "shareCookies": False, "requestCompressedContent": False},
         "onerror": [{"id": 30, "module": "builtin:Resume", "version": 1, "parameters": {},
                      "mapper": {"statusCode": 599}, "metadata": meta(2400)}],
         "metadata": meta(2400)},
        {"id": 13, "module": "builtin:BasicRouter", "version": 1, "parameters": {}, "mapper": None, "metadata": meta(2700),
         "routes": [
             {"flow": [
                 {"id": 14, "module": "builtin:BasicFeeder", "version": 1, "parameters": {},
                  "mapper": {"array": "{{12.data.documents}}"},
                  "filter": flt("A 200 complete/partial",
                                [cond("{{12.statusCode}}", "number:equal", "200"), cond("{{12.data.status}}", "text:equal", "complete")],
                                [cond("{{12.statusCode}}", "number:equal", "200"), cond("{{12.data.status}}", "text:equal", "partial")]),
                  "metadata": meta(3000)},
                 {"id": 15, "module": "microsoft-sharepoint:uploadAFile", "version": 1,
                  "parameters": {"__IMTCONN__": SHAREPOINT_CONN},
                  "mapper": {"enter": "path", "select": "map", "site": "{{2.spSite}}", "drive": "{{2.spDrive}}",
                             "folder": "{{2.spFolder}}", "filename": "{{14.fileName}}",
                             "data": "{{toBinary(14.pdfBase64; \"base64\")}}", "conflictBehavior": "replace"},
                  "filter": flt("generated PDF only", [cond("{{14.status}}", "text:equal", "generated"),
                                                        cond("{{14.pdfBase64}}", "exist")]),
                  "onerror": [record(31, "upload-failed", {"code": "SP_UPLOAD_FAILED"}, 3300),
                              {"id": 32, "module": "builtin:Ignore", "version": 1, "parameters": {}, "mapper": None,
                               "metadata": meta(3300)}],
                  "metadata": meta(3300)},
                 record(17, "filed", {"engineStatus": "{{12.data.status}}", "requestDigest": "{{12.data.requestDigest}}",
                                      "fileName": "{{14.fileName}}", "templateId": "{{14.templateId}}",
                                      "webUrl": "{{15.webUrl}}", "sharepointItemId": "{{15.id}}"}, 3600)]},
             {"flow": [record(18, "failed", None, 3000)],
              "filter": flt("B permanent error", *[[cond("{{12.statusCode}}", "number:equal", s)] for s in
                                                    ("400", "401", "404", "409", "413", "422")])},
             {"flow": [record(19, "{{if(8.i = 3; \"failed-after-retries\"; \"retrying\")}}", None, 3000)],
              "filter": flt("C retryable", *[[cond("{{12.statusCode}}", "number:equal", s)] for s in
                                              ("429", "500", "502", "503", "504", "599")])},
         ]},
    ]
    flow.append({"id": 5, "module": "builtin:BasicRouter", "version": 1, "parameters": {}, "mapper": None,
                 "metadata": meta(900), "routes": [
                     {"flow": [{"id": 6, "module": "datastore:AddRecord", "version": 1, "parameters": {"datastore": 0},
                                "mapper": {"key": "refused-{{2.runId}}", "overwrite": True,
                                           "data": {"status": "refused", "code": "{{4.result.refused}}",
                                                    "itemId": "{{ifempty(2.itemId; \"synthetic\")}}", "runId": "{{2.runId}}",
                                                    "at": "{{formatDate(now; \"YYYY-MM-DDTHH:mm:ssZ\")}}"}},
                                "metadata": meta(1200)}],
                      "filter": flt("refused before any call", [cond("{{4.result.refused}}", "exist")])},
                     {"flow": [
                         {"id": 7, "module": "datastore:GetRecord", "version": 1, "parameters": {"datastore": 0},
                          "mapper": {"key": "{{4.result.idempotencyKey}}"},
                          "filter": flt("built", [cond("{{4.result.refused}}", "notexist")]), "metadata": meta(1200)},
                         {"id": 8, "module": "builtin:BasicRepeater", "version": 1, "parameters": {},
                          "mapper": {"start": 1, "repeats": 3, "step": 1},
                          # F2: duplicate prevention: an already filed key is never processed again
                          "filter": flt("F2 not already filed", [cond("{{7.status}}", "text:notequal", "filed")]),
                          "metadata": meta(1350)},
                     ] + retry_tail}]})
    name = ("RSFA TEST – End Forms → SharePoint (synthetic T1)" if mode == "synthetic"
            else "RSFA TEST – End Forms → SharePoint (approved test item)")
    bp = {"name": name, "flow": flow,
          "metadata": {"instant": False, "version": 1,
                       "scenario": {"roundtrips": 1, "maxErrors": 3, "autoCommit": True, "autoCommitTriggerLast": True,
                                    "sequential": True, "confidential": True, "dataloss": False, "dlq": False,
                                    "freshVariables": False},
                       "designer": {"orphans": []}}}
    return bp


def interface(mode: str) -> list:
    if mode != "monday":
        return []
    return [{"name": "itemId", "type": "text", "required": True,
             "help": "Masterboard item id; must be listed in approvedItemIds (module 2)"}]


def main() -> None:
    OUT.mkdir(parents=True, exist_ok=True)
    for mode in ("synthetic", "monday"):
        (OUT / f"blueprint.{mode}.json").write_text(json.dumps(build(mode), ensure_ascii=False, indent=1) + "\n", encoding="utf-8")
        (OUT / f"interface.{mode}.json").write_text(json.dumps({"input": interface(mode), "output": []}, indent=1) + "\n")
    (OUT / "scheduling.json").write_text(json.dumps({"type": "on-demand"}) + "\n")
    print("written:", sorted(p.name for p in OUT.glob("*.json")))


if __name__ == "__main__":
    main()
