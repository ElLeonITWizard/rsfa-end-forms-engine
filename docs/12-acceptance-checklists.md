# 12 — Acceptance testing checklists for Rod (GHL Individual and GHL Trust)

**What this is:** a short list of things to check on the generated PDFs before RSFA decides whether these two forms
may be produced by the new engine.

- Use **test (fictional) clients** only. The review pack contains ready-made samples.
- Nothing here is approved yet.
- MacroForge keeps producing every form until RSFA decides otherwise.

**How to answer:** tick ✅, or write what is wrong next to the item. One line per problem is enough.

---

## A. GHL Account Use Questionnaire — Individual

**Before you start:** open the generated PDF next to the client's Masterboard item, the test client in the sample.

**Page 1: who the form is for**
- [ ] The customer's **full name** is the one on the Masterboard.
- [ ] The **occupation** is correct.

**What the accounts will be used for**
- [ ] The box ticked for "intend to use your accounts for" matches the Masterboard answer.
- [ ] If the Masterboard has **both** answers selected, the form is **not** produced (the PDF can record only one).
      Is that acceptable, with the adviser completing it by hand?
- [ ] "Total credits or deposits (next 12 months)": the ticked band matches, including **$150,001–$250,000**.
      MacroForge currently leaves this band blank because the Monday label was renamed.

**Cash deposits**
- [ ] Frequency ticked correctly, including **"Occasionally (monthly / quarterly)"**.
- [ ] Average value per deposit and the activity details match.
- [ ] Choosing **"Never"** while still giving an amount or details: the form is refused, with the message
      "contradictory". Is refusing the right behaviour?

**International payments**
- [ ] Frequency, average value and the details (countries) match.
- [ ] Question: the average value comes from the **ASB** column (as in MacroForge). A "(GHL Individual)" column with
      the same bands also exists. **Which one do advisers actually fill?**

**Section 5: anything other than personal use**
- [ ] Yes/No and the details are filled from the Masterboard. MacroForge leaves this section empty today.
- [ ] Answering "No" while giving details: the form is refused. Is that right?

**Always**
- [ ] **No signature, date of signing or declaration** is filled in by the engine.
- [ ] Nothing on the form belongs to a different client.
- [ ] Missing customer name: the form is **not** produced. Is that right?

---

## B. GHL Account Use Questionnaire — Trust

**Page 1: the trust**
- [ ] The **full trust/organisation name** is correct.
- [ ] The **objective** of the trust matches.
- [ ] "Why have you come to Sovereign Home Loans": the right box, and "other reasons" text if any.

**Credits**
- [ ] "Total credits or deposits (next 12 months)" band matches.
- [ ] "Sources of credits": **every** selected source is ticked (donations, dividends, other), and "other" text is
      shown. MacroForge's legacy rule compares the whole selection, so with several sources it probably ticks none
      (not verified against a MacroForge output).

**Cash deposits (3c)**
- [ ] Frequency and details match.
- [ ] The **average value per deposit** box is left blank on purpose: no Monday column has these bands. Should
      advisers keep completing it by hand, or should a column be added?

**International transfers IN (3d) and OUT (3e): the important fix**
- [ ] The **IN** details (3d) show only the incoming activity and countries.
- [ ] The **OUT** details (3e) show only the outgoing activity and countries.
- [ ] If there are **no outgoing** transfers, 3e is **empty**. MacroForge copies the same text into both boxes today.
- [ ] Average value IN and OUT ticked correctly, including **$1,000–$10,000**.
- [ ] "Never" with an amount or details: the form is refused as contradictory. Is that right?

**Third parties**
- [ ] Yes/No and purpose match. "No" together with a purpose is refused.

**Always**
- [ ] No signature, date of signing or declaration filled.
- [ ] Missing trust name: the form is not produced.

---

## C. Business decisions needed (the engine does not guess)

1. **RSFA-DECISION-3 — which GHL questionnaire applies to which client?**
   - Today MacroForge produces **both** the Individual and the Trust questionnaire for **every** ASB deal.
   - For a client without a trust, that means a Trust form with no trust name.
   - The engine refuses a Trust form without a trust name. Options:
     - (a) produce the Trust form only when the trust name is filled in;
     - (b) produce the Individual form only for individual clients;
     - (c) keep MacroForge's behaviour (both always).
2. **Average value columns:** which Monday columns advisers fill for the Individual's international average value
   (question in section A).
3. **Accept for testing in the new engine?**
   - Yes / no, per form.
   - A "yes" is recorded as an approval (who, date, reference) before the form is added to the cloud service. It is
     never recorded on RSFA's behalf.

## D. Where the samples are

The iteration review pack (`.local/review/iteration8/`, sent separately) contains:
- the filled sample PDFs;
- the browser screenshots.

All names, addresses and amounts in them are fictional.
