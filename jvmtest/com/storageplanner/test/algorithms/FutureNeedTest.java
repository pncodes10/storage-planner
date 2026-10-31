package com.storageplanner.test.algorithms;

import com.storageplanner.core.FileItem;
import com.storageplanner.algorithms.Decision;
import com.storageplanner.algorithms.v2_futureneed.FutureNeed;
import com.storageplanner.tools.category.PhotoCategory;

/** Will a picture be needed again: hand-written cases for every kind, the dates that matter, and the safety floors. */
public final class FutureNeedTest {
    static int checks, fails;
    static final long NOW = 1792000000000L;     // 14 Oct 2026
    static final long DAY = 86400000L;

    static FileItem pic(String rel, String area, String category, String text, double ageDays) {
        FileItem f = new FileItem(rel, "/sdcard/" + rel, 300000, NOW - (long) (ageDays * DAY));
        f.area = area; f.type = "png"; f.category = category; f.text = text == null ? null : text.toLowerCase();
        return f;
    }

    static Decision expect(String what, FileItem f, int verdict, String kind) {
        Decision a = FutureNeed.assess(f, NOW);
        checks++;
        boolean ok = a.verdict == verdict && (kind == null || kind.equals(a.kind));
        if (!ok) { fails++; System.out.printf("FAIL %s: got %s/%s need %.2f (%s), want %d/%s%n", what, a.verdict, a.kind, a.need, a.why, verdict, kind); }
        return a;
    }

    static final String SHOT = "Pictures/Screenshots/Screenshot_1.png", WA = "WhatsApp/Media/WhatsApp Images/IMG-1.jpg";
    static final int K = Decision.KEEP, U = Decision.UNSURE, D = Decision.NOT_NEEDED;

    public static void main(String[] args) {
        // one-time codes: gone after a day
        expect("fresh OTP", pic(SHOT, "screenshot", PhotoCategory.OTP, "123456 is your OTP. Do not share", 0.1), K, "otp");
        expect("old OTP", pic(SHOT, "screenshot", PhotoCategory.OTP, "123456 is your OTP. Do not share", 3), D, "otp");
        // payments: what was paid for and how much matters
        expect("small old payment", pic(SHOT, "screenshot", PhotoCategory.PAYMENT, "Payment successful ₹250 paid to Fresh Mart UPI transaction ID 412345678901", 150), D, "payment");
        expect("small recent payment", pic(SHOT, "screenshot", PhotoCategory.PAYMENT, "Payment successful ₹250 paid to Fresh Mart UPI transaction ID 412345678901", 20), K, "payment");
        expect("rent payment is proof", pic(SHOT, "screenshot", PhotoCategory.PAYMENT, "Paid to Suresh Rao ₹18,000 house rent for October UPI ref 412345678901", 400), K, "payment_proof");
        expect("large payment is proof", pic(SHOT, "screenshot", PhotoCategory.PAYMENT, "Money sent ₹45,000 to Ravi Kumar UPI transaction ID 412345678901", 700), K, "payment_proof");
        expect("mid payment kept a year", pic(SHOT, "screenshot", PhotoCategory.PAYMENT, "Paid to Croma ₹4,999 UPI transaction ID 412345678901 Google Pay", 200), K, "payment_big");
        expect("amount not readable: may be large, never suggested", pic(SHOT, "screenshot", PhotoCategory.PAYMENT, "Paid successfully to Rahul Verma UPI transaction ID 021419848169", 600), U, "payment_unknown");
        expect("failed payment, nothing debited", pic(SHOT, "screenshot", PhotoCategory.PAYMENT, "Payment failed ₹2,339 paid to Fresh Mart No amount was debited. Retry", 60), D, "payment_failed");
        expect("failed payment", pic(SHOT, "screenshot", PhotoCategory.PAYMENT, "Payment failed ₹300 to Zomato transaction ID 412345678901 Google Pay", 30), D, "payment_failed");
        expect("failed, money debited", pic(SHOT, "screenshot", PhotoCategory.PAYMENT, "Payment failed ₹300 money debited will be refunded in 5 days UPI ref 412345678901", 20), K, "payment_pending");
        // tickets: until the trip is over
        FileItem tk = pic(SHOT, "screenshot", PhotoCategory.TICKET, "IRCTC PNR 4512345678 Date of Journey 12-Nov-2026", 5);
        tk.catDate = NOW + 29 * DAY;
        expect("upcoming trip", tk, K, "ticket");
        FileItem past = pic(SHOT, "screenshot", PhotoCategory.TICKET, "IRCTC PNR 4512345678 Date of Journey 12-Aug-2026", 70);
        past.catDate = NOW - 63 * DAY;
        expect("past trip", past, D, "ticket");
        FileItem refund = pic(SHOT, "screenshot", PhotoCategory.TICKET, "PNR 4512345678 ticket cancelled refund of ₹1,240 initiated", 10);
        refund.catDate = NOW - 12 * DAY;
        expect("cancelled ticket awaiting refund", refund, K, "ticket_refund");
        // coupons: until they expire
        expect("valid coupon", pic(SHOT, "screenshot", null, "Use code SAVE20 for 20% off on your next order. Offer valid till 30 Nov 2026. T&C apply", 10), K, "coupon");
        expect("expired coupon", pic(SHOT, "screenshot", null, "Use code SAVE20 for 20% off on your next order. Offer valid till 30 Aug 2026. T&C apply", 60), D, "coupon");
        expect("expired coupon, no year", pic(SHOT, "screenshot", null, "Scratch card: you won ₹50 cashback voucher. Expires on 5 Sep. Redeem now", 50), D, "coupon");
        // delivery and rides
        expect("old delivery", pic(SHOT, "screenshot", null, "Your order has been delivered. Delivered on 2 Jul 2026. Order # 404-1234567. Return window closes 12 Jul", 104), D, "delivery");
        expect("arriving tomorrow", pic(SHOT, "screenshot", null, "Arriving tomorrow. Out for delivery. Track package. Order # 404-1234567", 1), K, "delivery");
        expect("old cab ride", pic(SHOT, "screenshot", null, "Your ride is arriving. Driver Ramesh is 3 mins away. Ride OTP 4821. Uber", 60), D, "ride");
        expect("maps", pic(SHOT, "screenshot", null, "Directions 12 km 25 min via MG Road fastest route usual traffic Start navigation", 40), D, "navigation");
        expect("weather", pic(SHOT, "screenshot", null, "Bengaluru 24°C Partly cloudy Feels like 26 Humidity 78% Chance of rain 40% Hourly forecast", 20), D, "weather");
        expect("shopping", pic(SHOT, "screenshot", null, "boAt Rockerz 450 ₹1,299 MRP ₹3,990 67% off Add to cart Buy now Free delivery Ratings 4.1", 120), D, "shopping");
        // records are always kept, however old and whatever else they mention
        expect("warranty card", pic("DCIM/Camera/IMG_1.jpg", "camera", PhotoCategory.DOCUMENT, "Warranty card Model no SM-A546 Serial no R58N12345 Date of purchase 12/03/2024 Dealer", 900), K, "warranty");
        expect("electronics receipt with IMEI", pic("DCIM/Camera/IMG_2.jpg", "camera", PhotoCategory.RECEIPT, "Reliance Digital Tax invoice Samsung Galaxy IMEI 351234567890123 Total 24,999.00 Cash Thank you", 800), K, "warranty");
        expect("prescription", pic(WA, "wa_image", PhotoCategory.DOCUMENT, "Dr. Anita Rao MBBS Rx Tab. Paracetamol 500 mg twice daily after food for 5 days patient Ravi", 600), K, "medical");
        expect("insurance", pic(SHOT, "screenshot", null, "Star Health Insurance Policy number P/123/456 Sum insured ₹5,00,000 Nominee Priya Premium due 12 Jan", 500), K, "insurance");
        expect("wifi password", pic(SHOT, "screenshot", null, "WiFi network name Airtel_Ravi password: ravi@1234", 700), K, "credential");
        expect("dentist appointment is not a medical record", pic(SHOT, "screenshot", null, "Appointment confirmed Dr. Rahul Verma (Dentist) Apollo Clinic Date: 20 Aug 2026 Time 11:30 AM Reschedule", 70), D, "appointment");
        expect("greeting card the sorter called a graphic", pic(WA, "wa_image", PhotoCategory.GRAPHIC, "good nighit stay blessed", 40), D, "greeting");
        expect("bank details", pic(WA, "wa_image", null, "Account holder Meena Patel Account number 50100123456789 IFSC HDFC0001234 Branch Indiranagar", 900), K, "credential");
        expect("ID", pic("Download/aadhaar.jpg", "download", PhotoCategory.ID, null, 2000), K, "id");
        // memories
        FileItem fam = pic("DCIM/Camera/IMG_3.jpg", "camera", PhotoCategory.PEOPLE, null, 3000); fam.faces = 3;
        expect("family photo", fam, K, "people");
        expect("landscape", pic("DCIM/Camera/IMG_4.jpg", "camera", PhotoCategory.PHOTO, null, 3000), K, "photo");
        expect("accidental", pic("DCIM/Camera/IMG_5.jpg", "camera", PhotoCategory.ACCIDENTAL, null, 3), D, "accidental");
        // greetings and forwards
        expect("old greeting", pic(WA, "wa_image", PhotoCategory.GREETING, "Good morning have a blessed day", 20), D, "greeting");
        expect("new greeting", pic(WA, "wa_image", PhotoCategory.GREETING, "Good morning have a blessed day", 2), K, "greeting");
        FileItem gf = pic(WA, "wa_image", PhotoCategory.GREETING, "Happy birthday", 60); gf.faces = 2;
        expect("greeting with people in it", gf, U, "greeting");
        expect("old meme", pic(WA, "wa_image", null, "When you check your bank balance after the weekend lol forwarded", 90), D, "forward");
        // uncertain kinds never go below the floor
        expect("old plain screenshot", pic(SHOT, "screenshot", PhotoCategory.SCREENSHOT, "settings network internet battery", 2000), U, "screenshot");
        expect("old chat", pic(SHOT, "screenshot", PhotoCategory.CHAT, "ravi online are you coming 7:42 pm", 2000), K, "chat");
        expect("old bill", pic("Download/bill.png", "download", PhotoCategory.BILL, "invoice no 42 amount due 1,845.00 due date", 2000), K, "bill");
        expect("not read yet", pic(SHOT, "screenshot", PhotoCategory.SCREENSHOT, null, 900), U, null);
        // small receipts go eventually, large ones stay
        expect("small old receipt", pic("DCIM/Camera/IMG_6.jpg", "camera", PhotoCategory.RECEIPT, "Sri Krishna Sweets Total 535.50 Cash 600.00 Change 64.50 Thank you", 400), D, "receipt_small");
        expect("large receipt", pic("DCIM/Camera/IMG_7.jpg", "camera", PhotoCategory.RECEIPT, "Furniture World Total 38,500.00 Card Thank you", 2000), K, "receipt");
        // the folder you put it in counts
        expect("OTP in a folder you made", pic("Pictures/Important/otp.png", "pictures", PhotoCategory.OTP, "123456 is your OTP. Do not share", 30), K, "otp");
        expect("Screenshots folder is not a choice", pic("Pictures/Screenshots/otp.png", "screenshot", PhotoCategory.OTP, "123456 is your OTP. Do not share", 30), D, "otp");
        expect("consultation booking is not a medical record", pic(SHOT, "screenshot", null, "Consultation scheduled Dr. Priya Sharma (General Physician) Apollo Clinic Date: 30-Sep-2026 Time: 9:30 am Cancel appointment", 40), D, "appointment");
        expect("short meme caption on a chat image", pic(WA, "wa_image", PhotoCategory.DOCUMENT, "me explaining to my wife funny but true", 90), D, "forward");
        expect("short caption on a camera photo is not a forward", pic("DCIM/Camera/IMG_8.jpg", "camera", PhotoCategory.DOCUMENT, "funny but true", 400), K, "document");
        // sale ads: exactly what the reader found on IMG-20261031-WA0007 in the Algorithm Lab, then variants
        String LOGGED = "BUY 1GET | Sale is live";
        expect("sale ad from the Lab log, received 3 weeks ago", pic(WA, "wa_image", PhotoCategory.PHOTO, LOGGED, 21), D, "promo");
        expect("same ad, received yesterday", pic(WA, "wa_image", PhotoCategory.PHOTO, LOGGED, 1), K, "promo");
        expect("same ad as a screenshot", pic(SHOT, "screenshot", PhotoCategory.SCREENSHOT, LOGGED, 30), D, "promo");
        expect("same words on a photo you took: kept", pic("DCIM/Camera/IMG_9.jpg", "camera", PhotoCategory.PHOTO, LOGGED, 300), K, "photo");
        expect("buy one get one free", pic(WA, "wa_image", PhotoCategory.GRAPHIC, "Buy One Get One FREE on all pizzas. Order now", 40), D, "promo");
        expect("flash sale", pic(WA, "wa_image", PhotoCategory.GRAPHIC, "Flash sale upto 70% off shop now", 40), D, "promo");
        expect("ad with an end date still ahead", pic(WA, "wa_image", PhotoCategory.GRAPHIC, "Mega sale is live, flat 50% off. Offer ends 30 Nov 2026. Shop now", 20), K, "coupon");
        FileItem adFaces = pic(WA, "wa_image", PhotoCategory.PEOPLE, LOGGED, 40); adFaces.faces = 2;
        expect("ad with people in it", adFaces, K, "people");
        // amounts and dates
        checks++; if (FutureNeed.amount("biscuits 64,44 paneer 51.02") != 51.02) { fails++; System.out.println("FAIL decimal comma taken as thousands"); }
        checks++; if (FutureNeed.total("cash 600.00 change 64.50 total 535.50 thank you") != 535.5) { fails++; System.out.println("FAIL receipt total"); }
        checks++; if (FutureNeed.amount("paid ₹1,25,000 ref 412345678901") != 125000) { fails++; System.out.println("FAIL Indian grouping amount"); }
        checks++; if (FutureNeed.amount("upi ref 412345678901 order 1234") != 0) { fails++; System.out.println("FAIL reference number taken as money"); }
        checks++; if (FutureNeed.amount("total %450 paid") != 450) { fails++; System.out.println("FAIL rupee sign read as %"); }
        Decision c = FutureNeed.assess(pic(SHOT, "screenshot", null, "Use code SAVE20 for 20% off. Offer valid till 30 Nov 2026", 10), NOW);
        checks++; if (c.deletableFrom <= c.usefulUntil) { fails++; System.out.println("FAIL coupon deletable before it expires"); }
        System.out.println((checks - fails) + "/" + checks + " future-need checks pass");
        if (fails > 0) System.exit(1);
    }
}
