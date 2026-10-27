# 05 — Demo script: RSFA End Forms Engine (meeting with Rod)

A 5-minute walk-through of the demo page. **Synthetic data only.** Do not type real client details into the demo.

## Before the meeting (10 minutes, presenter only)

Pick **one** way to open the page and test it once before the meeting.

### Option 1 — Cloud Run, through `gcloud run services proxy` (after `deploy/deploy_poc.sh` has run)

- The service stays private. Cloud Run IAM rejects every request without a Google identity token, and the demo page is
  covered by the same rule (`deploy_poc.sh` checks it: 403 without a token, 200 with one).
- `gcloud run services proxy` signs every request as **you**, using your own `gcloud` login. It listens only on your
  machine, so the page and the API share one local origin. No token is copied, pasted or stored.

```bash
gcloud run services proxy rsfa-end-forms-engine \
  --region=australia-southeast1 --project=rsfa-end-forms-engine --port=8080
# keep it running; open http://localhost:8080/ in Chrome or Edge
```

- Only identities with `roles/run.invoker` can use the proxy. `deploy_poc.sh` grants this to the deployer only.
  Granting it to anyone else, such as Rod, is a separate IAM change that needs RSFA's approval.
- In **Cloud Shell**, use *Web Preview → Preview on port 8080* instead of `localhost`. Only your Google login can open
  that preview URL. *(Not verified from this repository: no Google Cloud access here.)*
- Revision `rsfa-end-forms-engine-00001-6qm` has no page. Revision 2 adds it: RSFA authorised it and runs it from
  Cloud Shell (docs/04 "Revision 2"). Rolling back to revision 1 is a single command. Option 2 works without any deployment.

### Option 2 — Your own computer (no cloud)

Requires Java 21 and the extracted blank templates in `.local/generated/templates` (see README).

```bash
(cd engine && mvn -B -q package -DskipTests)     # or use a jar you have already built and tested
ENGINE_AUTH=NONE java -jar engine/target/end-forms-engine.jar serve
# listens on 127.0.0.1:8080 only; open http://localhost:8080/
```

- `ENGINE_AUTH=NONE` is refused on any non-loopback address, so nobody else on the network can reach it.
- Docker alternative: `docker run --rm -p 127.0.0.1:8080:8080 -e ENGINE_AUTH=PLATFORM_IAM -e K_SERVICE=demo <poc image>`
  (only on your own machine; this is how the demo was tested).

**Checklist.** The top-right badge says **"Engine online · 1 form ready"**. This counts the forms the page offers that
the engine can actually generate. Then do one Generate with the sample data.

## During the meeting — what to click

| # | Click / do | Say |
|---|---|---|
| 1 | Open the page. Point at the green **Engine online** badge. | "This is the new engine that fills the bank forms. Make will call it instead of MacroForge. The adviser's Monday process does not change." |
| 2 | **Lender form** → *BNZ — Boarder Income Declaration*. | "We start with one real BNZ form, the blank official PDF. More lenders come after this one is signed off." |
| 3 | Click **Load Sample Data**. | "These are made-up applicants. In production, this data arrives from Monday through Make." |
| 4 | Change **Applicant 1 — full name**, e.g. to `Aroha Tēst-Example`. The edited box turns yellow. | "Any value can change, and macrons are supported." |
| 5 | Click **Generate PDF**. The button shows *Generating…*, then a green **PDF generated** message. | "It takes well under a second." |
| 6 | Point at the preview: the edited name sits in **Customer name(s)**. The address, rooms and weekly amount are in their boxes, and both dates read 27 10 26. | "Every value goes into the same place on the lender's own form." |
| 7 | Point at **Produced by: Deterministic mapping engine — not AI** and at the **fingerprint**. | "No AI decides anything. The mapping is a reviewed file under version control. The same input always gives the identical PDF, byte for byte. That fingerprint lets us prove it." |
| 8 | Scroll the preview to **Signature of customer** and the declaration. | "Signatures and declarations stay blank for the customer. The engine never signs, and it does not tick a declaration unless RSFA has recorded an approval for that rule." |
| 9 | Click **Download PDF**. Open the file. | "This is the file that would be filed to SharePoint. It is still an editable PDF." |
| 10 | *(Optional, error handling.)* Put an emoji in Applicant 1, then **Generate PDF**. A red message names the field and the reason. Click **Load Sample Data** again afterwards. | "If something is wrong, nothing is produced. The engine says exactly which field and why. It never quietly sends a broken form." |

**Optional determinism proof.** Click **Load Sample Data** and then **Generate PDF** without editing anything. The
fingerprint is `2ae7bfac…3269d6f6`, every time, on any machine. It is the same value recorded in docs/04.

## If something goes wrong

| Symptom | Fix |
|---|---|
| Badge says **Engine not reachable** | The server or the `gcloud run services proxy` command stopped. Start it again and reload the page. |
| **Not authorised** (401/403) | The proxy is not running, or your account lacks `run.invoker`. Do not change IAM during the meeting. Use Option 2. |
| **TEMPLATE_UNAVAILABLE** | The blank BNZ PDF is missing on that server. Locally, run the `extract` step from the README. |
| Preview area stays empty | The browser has no built-in PDF viewer. Use **Download PDF** (Chrome and Edge show the preview). |

## What the demo does not show (say so if asked)

- Only BNZ Boarder is enabled. 22 other imported lender forms are not yet tested end to end (docs/03).
- Make, Monday and SharePoint are not connected yet. Make will call `POST /generate`. The service-to-service
  authentication is still an open decision.
- Equivalence with MacroForge output is not proven yet. The next step is a parallel run on the same inputs.
- This page is a demo, not a product. It has no template upload, mapping editor, user management or database.
