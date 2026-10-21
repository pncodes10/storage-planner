package com.storageplanner.test.tools;

import com.storageplanner.tools.category.PhotoCategory;
import com.storageplanner.tools.ocr.TextRules;

import java.util.Calendar;
import java.util.TimeZone;

/** Text rules on hand-written examples: each kind, ID documents (never anything else), look-alikes, dates. */
public final class TextRulesTest {
    static int checks, fails;
    static final long NOW = 1792000000000L;     // Oct 2026

    static void expect(String what, String text, double aspect, boolean photo, boolean shot, boolean chat, String kind) {
        TextRules.Words w = new TextRules.Words();
        for (String t : text.split(" ")) if (!t.isEmpty()) w.add(t, 92);
        TextRules.Verdict v = TextRules.classify(w, aspect, photo, shot, chat, NOW);
        checks++;
        boolean ok = kind == null ? v.kind == null : kind.equals(v.kind);
        if (!ok) { fails++; System.out.println("FAIL " + what + ": got " + v.kind + " (" + v.why + "), want " + kind); }
    }

    public static void main(String[] a) {
        expect("OTP", "123456 is your OTP for login to HDFC Bank. Do not share it with anyone.", 0.45, false, true, false, PhotoCategory.OTP);
        expect("verification code", "Your Swiggy verification code is 4821. It expires in 10 minutes.", 0.45, false, true, false, PhotoCategory.OTP);
        expect("UPI payment", "Payment successful ₹1,250 Paid to Sri Balaji Stores UPI transaction ID 412345678901 From: HDFC Bank Powered by Google Pay", 0.45, false, true, false, PhotoCategory.PAYMENT);
        expect("PhonePe", "Transaction Successful Paid to Ramesh Rs. 500 Debited from SBI UTR 123456789012 PhonePe", 0.45, false, true, false, PhotoCategory.PAYMENT);
        expect("train ticket", "IRCTC e-Ticket PNR 4512345678 Train No. 12951 Date of Journey 12-Nov-2026 Coach B3 Berth 45 Booking Status CNF", 0.45, false, true, false, PhotoCategory.TICKET);
        expect("boarding pass", "BOARDING PASS IndiGo Flight 6E 211 From DEL To BOM Gate 12 Seat 14C Boarding 18:20 Sequence 45", 0.45, false, true, false, PhotoCategory.TICKET);
        expect("receipt", "SRI KRISHNA SWEETS Bill No 1234 Cashier Ravi Qty Item Price Amount Kaju Katli 1 450.00 450.00 Samosa 4 15.00 60.00 Sub Total 510.00 CGST 2.5% 12.75 SGST 2.5% 12.75 Total 535.50 Cash 600.00 Change 64.50 Thank you come again",
               0.4, false, false, false, PhotoCategory.RECEIPT);
        expect("invoice", "TAX INVOICE Invoice No INV-2026-0042 Invoice Date 03/09/2026 Bill To Acme Pvt Ltd GSTIN 29ABCDE1234F1Z5 Description HSN Qty Unit Price Amount Consulting 998311 10 2,000.00 20,000.00 IGST 18% 3,600.00 Total Due 23,600.00 Due Date 30/09/2026 Bank Details",
               0.71, false, false, false, PhotoCategory.BILL);
        expect("electricity bill", "BESCOM Electricity Bill Consumer No 1234567 Billing Period Aug 2026 Units Consumed 245 Amount Due Rs. 1,845.00 Due Date 15-09-2026",
               0.71, false, false, false, PhotoCategory.BILL);
        expect("greeting", "Good Morning Have a blessed day", 1.0, true, false, true, PhotoCategory.GREETING);
        expect("greeting, one word, chat image", "Happy", 1.0, true, false, true, PhotoCategory.GREETING);
        expect("one greeting word on a camera photo is not enough", "Happy", 1.33, true, false, false, null);
        expect("chat", "Ravi online Hi are you coming today 7:42 pm yes will reach by 8 7:43 pm ok see you 7:43 pm Type a message", 0.45, false, true, false, PhotoCategory.CHAT);
        // sensitive documents are recognised first, whatever else the text says
        expect("Aadhaar", "Government of India Ravi Kumar DOB: 01/01/1990 Male 1234 5678 9012 Aadhaar Mera Aadhaar Meri Pehchaan", 1.58, false, false, false, PhotoCategory.ID);
        expect("PAN", "INCOME TAX DEPARTMENT GOVT. OF INDIA Permanent Account Number ABCDE1234F Name RAVI KUMAR Date of Birth 01/01/1990", 1.58, false, false, false, PhotoCategory.ID);
        expect("passport", "REPUBLIC OF INDIA Passport No. Z1234567 Nationality INDIAN Date of Birth 01/01/1990 Place of Issue BENGALURU", 1.42, false, false, false, PhotoCategory.ID);
        // look-alikes that must not become ID documents or payments
        expect("news about Aadhaar is a screenshot, not an ID", "The government said on Monday that Aadhaar linking deadlines for bank accounts will be extended by three months", 0.45, false, true, false, null);
        expect("a few words", "hello there", 0.45, false, true, false, null);
        expect("article page", "The monsoon session of parliament began on Monday and markets closed higher as banking stocks rallied while scientists say the new telescope images reveal distant galaxies and the city will add new electric buses this year experts say",
               0.71, false, false, false, PhotoCategory.DOCUMENT);
        // dates
        checks++;
        long d = TextRules.date("date of journey 12-nov-2026 booked 01/10/2026", NOW);
        Calendar c = Calendar.getInstance(TimeZone.getTimeZone("UTC")); c.setTimeInMillis(d);
        if (c.get(Calendar.YEAR) != 2026 || c.get(Calendar.MONTH) != Calendar.NOVEMBER || c.get(Calendar.DAY_OF_MONTH) != 12) { fails++; System.out.println("FAIL latest date: " + c.getTime()); }
        checks++;
        if (TextRules.date("call 98450 12345 or 31/02/2026", NOW) != 0) { fails++; System.out.println("FAIL impossible date accepted"); }
        System.out.println((checks - fails) + "/" + checks + " text rule checks pass");
        if (fails > 0) System.exit(1);
    }
}
