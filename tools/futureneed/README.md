# Will a picture be needed again? (FutureNeed test phone)

How `FutureNeed` (app/src/main/java/com/storageplanner/core/FutureNeed.java) was built and tested. The pictures
are not included; these scripts make them again.

## The plan it followed

1. **Read what the picture is for**, beyond its category: records (warranty, prescription and lab reports,
   insurance, certificates and contracts, Wi-Fi and bank details) versus short-lived content (one-time codes,
   coupons, delivery and ride updates, maps, weather, parking slips, shopping pages, appointments, forwards).
   Payments by amount, purpose (rent, fees, EMI…) and state (failed, pending, refund).
2. **Read the dates that matter**: coupon expiry, trip date, delivery date, appointment day.
3. **Give each kind a useful life**: full value while useful, then halving down to a floor. Kinds without
   a clear end (screenshots in general, documents, chats, photos) have a floor that can never be suggested.
4. **Let the surroundings count**: faces in the picture, a folder you named yourself ("Pictures/Tax 2025"),
   text not read yet.
5. **Test on pictures, end to end**, then fix the misses that point to a general rule (never phrase lists from
   the misses), and repeat.

## Making the test phone

```
python3 tools/futureneed/build.py dev   /tmp/fn_dev     # rules are tuned on this one
python3 tools/futureneed/build.py test  /tmp/fn_test    # held out, measured after each round
python3 tools/futureneed/build.py final /tmp/fn_final   # fresh held-out set, made once at the end
python3 tools/futureneed/ocr.py /tmp/fn_final           # the app's own text reader, as on the phone
java -cp <classes> com.storageplanner.test.FutureNeedCorpusTest /tmp/fn_final --list --check
```

Needs Playwright with Chromium, Pillow and numpy.

- `build.py` renders 368 pictures per set:
  - phone screens at 1080 × 2400 in Chromium;
  - chat images (greetings, memes, invitations, bank details);
  - paper photographed on a table (receipts, prescriptions, warranty cards);
  - plain camera photos, some with faces (`.faces` sidecar, as for `CategoryTest`).
- Each picture is labelled **delete** (nothing in it is needed on 14 Oct 2026) or **keep** (needed, or not
  safe to suggest), and its file date is set to when it was saved.
- The sets use different random seeds. The held-out sets also use different wordings for every kind
  ("Offer valid till" / "Offer ends", "Paid to" / "Sent to"…).
- `ocr.py` reads every picture with the app's offline Tesseract page, served exactly as `OcrEngine` serves it
  (no outside requests), and writes `<picture>.ocr.json` for `JvmPlatform`.

## Results

Each round ran on the dev set first; "held-out" is the `test` set, then the fresh `final` set.
**Caught** counts not-needed pictures suggested, out of 144. **Wrong** counts still-needed pictures
suggested, out of 224.

| Round | What changed | Dev: caught | Dev: wrong | Held-out: caught | Held-out: wrong |
|---|---|---|---|---|---|
| 1 | First version | 129 | 22 | – | – |
| 2 | Unreadable amount is never "small"; failed vs. refunded wording; stricter medical evidence; greetings read from text whatever the category; camera-sharp test photos | 127 | 0 | – | – |
| 3 | Low-confidence amounts kept (see below); black camera frames are accidental | 135 | 0 | 127 (test) | 0 |
| 4 | Valid digit grouping only, receipt total; records need one strong phrase; short captions on chat images | 135 | 0 | 131 (test) | 0 |
| final | Nothing (fresh pictures) | – | – | **132 (91.7%)** | **0** |

**The rules from version 1.6, on the same final pictures:**
- caught 33 of 144 (22.9%);
- wrongly suggested 31 of 224:
  - 8 payments of ₹15,000 and more;
  - 8 rent or fee payments;
  - 8 payments saved in a folder named "Important", "Bills" and so on;
  - 7 cancelled tickets still waiting for a refund.

**What is still missed:**
- **Payments whose amount can't be read reliably** (8 of 8 small old payments in the final set). Tesseract
  reads the big bold amount on payment screens with low confidence, and it has no rupee sign: "₹1,093" comes
  out as "71,093" or not at all. Misreading can only make an amount look larger, never smaller, so the app
  keeps such amounts as an upper bound. A payment whose amount is not read at all is **never** suggested
  ("your call"), because it may be a large one.
- **Memes with no telling words** (3 of 8). Words alone can't tell a caption from a note.
- **A greeting the reader could not read at all** (1 of 8).

**Not measured here:**
- The picture categories themselves (`CategoryTest`, whose corpus is not in this repository).
- Real phone screenshots: these are synthetic, made to look like the real apps.
