# Picture-category test corpus

How the 399 test pictures for `CategoryTest` were made. The pictures are not included.

**Real pictures:**
- receipts from SROIE (github.com/zzzDavid/ICDAR-2019-SROIE);
- invoices from invoice2data's test PDFs;
- document pages from the Tesseract and OCRmyPDF test files;
- the study's real phone photos;
- held-out faces.

**Synthetic pictures:** made by `build.py` (QR scenes, greetings, accidental shots) and `screens.py`
(payment, OTP, ticket, chat and ordinary screenshots, rendered in Chromium at 1080 x 2400).

`run_corpus.py` records what Tesseract (the same tesseract.js build as the app) reads in each picture, as
`<picture>.ocr.json`:
- `EDGE=1600` for the normal read;
- `EDGE=360 SUFFIX=.ocr360.json ONLY=WhatsApp` for the small second look at chat images.

Faces in the real photos were marked with YuNet as `<picture>.faces` (a stand-in for Android's detector).
