"""Builds a labelled test phone for FutureNeed: pictures whose content decides whether they are still needed.

Every picture is rendered (phone screens in Chromium at 1080 x 2400, chat images, photographed paper) and
labelled with what a careful person would do with it on the test date (14 Oct 2026): "delete" (nothing in it
is needed any more) or "keep" (needed, or at least not safe to suggest). Its file date is set to when it was
saved. Two sets are made with different random seeds and different wordings for each kind of picture:
'dev' (rules are tuned on it) and 'test' (held out; only measured). 'final' is a fresh held-out set (new seed,
held-out wordings), made once after the rules stopped changing, so the reported numbers come from pictures no
rule was ever adjusted for.

usage: build.py <dev|test|final> <out dir>        writes <out>/sdcard/... and <out>/labels.json"""
import io, json, math, os, random, sys
import numpy as np
from PIL import Image, ImageDraw, ImageFilter
from playwright.sync_api import sync_playwright

SET, OUT = sys.argv[1], sys.argv[2]
R = random.Random({'dev': 101, 'test': 202, 'final': 303}[SET])
NOW = 1792000000.0                       # seconds, 14 Oct 2026 (JvmPlatform's default clock)
DAY = 86400.0
MON = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec']
labels = {}


def alt(*options):
    """Wording variants: the dev set draws from the first half, the held-out set from the second half."""
    h = (len(options) + 1) // 2
    pool = options[:h] if SET == 'dev' else options[len(options) - h:]
    return R.choice(pool)


def when(days_ago):
    return NOW - days_ago * DAY


def fmt_date(t, style=None):
    import time
    g = time.gmtime(t)
    style = style or alt('dmy', 'd mon y', 'mon d, y', 'd-mon-y')
    if style == 'dmy': return f'{g.tm_mday:02d}/{g.tm_mon:02d}/{g.tm_year}'
    if style == 'd mon y': return f'{g.tm_mday} {MON[g.tm_mon - 1]} {g.tm_year}'
    if style == 'mon d, y': return f'{MON[g.tm_mon - 1]} {g.tm_mday}, {g.tm_year}'
    if style == 'd mon': return f'{g.tm_mday} {MON[g.tm_mon - 1]}'
    return f'{g.tm_mday:02d}-{MON[g.tm_mon - 1]}-{g.tm_year}'


def rupees(lo, hi):
    v = R.randint(lo, hi)
    return v, f'{v:,}' if v < 100000 else f'{v // 100000},{(v // 1000) % 100:02d},{v % 1000:03d}'


NAMES = ['Ramesh Kumar', 'Priya Sharma', 'Anil Traders', 'Sri Balaji Stores', 'Meena Patel', 'Rahul Verma', 'Kavya Nair',
         'Fresh Mart', 'Arjun Reddy', 'Lakshmi Iyer', 'Mohan Das', 'Sneha Joshi']
STATUS = '<div style="height:28px;background:#111;color:#fff;font:12px sans-serif;padding:6px 14px">9:41 &nbsp; 87%</div>'


def page(body, bg='#fff', font='Roboto, Arial, sans-serif'):
    return f'<html><body style="margin:0;background:{bg};font-family:{font}">{STATUS}{body}</body></html>'


def card(lines, title=None, color='#fff', size=17):
    t = f'<div style="font-size:24px;font-weight:bold;margin-bottom:10px">{title}</div>' if title else ''
    return f'<div style="margin:16px;padding:20px;background:{color};border-radius:14px;font-size:{size}px;line-height:1.8">{t}{"<br>".join(lines)}</div>'


# ------------------------------------------------------------------ screens (each returns html)
def otp():
    code = R.randint(100000, 999999); svc = R.choice(['HDFC Bank', 'Amazon', 'Swiggy', 'IRCTC', 'Flipkart', 'Paytm', 'Myntra', 'Zepto'])
    msg = alt(f'{code} is your OTP for login to {svc}. Do not share it with anyone.',
              f'Your {svc} verification code is {code}. It expires in 10 minutes.',
              f'Use {code} as your one time password for {svc}. Never share your OTP with anyone.',
              f'{svc}: {code} is the security code for your transaction. Valid for 5 minutes. Do not share.')
    return page(f'<div style="padding:12px 16px;font-size:20px;font-weight:bold">Messages</div>'
                f'<div style="margin:12px;padding:16px;background:#e8f0fe;border-radius:16px;font-size:18px;line-height:1.5">{msg}</div>')


def payment(amount_text, to, status='ok', purpose=None):
    upi = ''.join(R.choice('0123456789') for _ in range(12))
    app = R.choice(['Google Pay', 'PhonePe', 'Paytm', 'BHIM'])
    head = {'ok': alt('Payment successful', 'Paid successfully', 'Money sent', 'Transaction Successful'),
            'failed': alt('Payment failed', 'Transaction failed', 'Payment declined', 'Transaction unsuccessful'),
            'pending': alt('Payment pending', 'Transaction in progress', 'Payment processing', 'Payment pending')}[status]
    note = f'<div style="margin-top:8px;font-size:17px">Note: {purpose}</div>' if purpose else ''
    extra = ''
    if status == 'failed': extra = alt('<div style="margin-top:10px;color:#b00">Your money was not deducted. Please try again.</div>',
                                       '<div style="margin-top:10px;color:#b00">No amount was debited. Retry the payment.</div>')
    if status == 'pending': extra = alt('<div style="margin-top:10px">If money is debited, it will be refunded in 3-5 working days.</div>',
                                        '<div style="margin-top:10px">We are confirming this payment with your bank. Check back later.</div>')
    color = {'ok': '#1e8e3e', 'failed': '#c5221f', 'pending': '#e37400'}[status]
    return page(f'''<div style="padding:40px 24px;text-align:center"><div style="font-size:60px;color:{color}">●</div>
      <div style="font-size:22px;margin:8px">{head}</div><div style="font-size:44px;font-weight:bold">₹{amount_text}</div>
      <div style="font-size:18px;margin-top:10px">{alt("Paid to", "To", "Sent to")} {to}</div>{note}{extra}
      <div style="margin-top:30px;text-align:left;font-size:15px;line-height:2">UPI transaction ID<br><b>{upi}</b><br>
      From: {R.choice(["HDFC Bank", "State Bank of India", "ICICI Bank"])} ••{R.randint(1000, 9999)}<br>Powered by {app}</div></div>''')


def coupon(expiry_t=None):
    code = R.choice(['SAVE20', 'FLAT100', 'WELCOME50', 'DIWALI25', 'FEST300', 'NEWUSER', 'TRYNEW', 'BIGSALE'])
    shop = R.choice(['Myntra', 'Swiggy', 'Zomato', 'Ajio', 'Nykaa', 'BigBasket', 'Domino\'s', 'Uber'])
    off = alt(f'Flat ₹{R.choice([50, 75, 100, 150])} off on orders above ₹{R.choice([299, 499, 799])}', f'{R.choice([10, 20, 25, 40])}% off on your next order',
              f'Get {R.choice([15, 30, 50])}% cashback up to ₹{R.choice([100, 200])}', f'Buy 1 get 1 free on all pizzas')
    head = alt(f'Use code <b>{code}</b>', f'Coupon code: <b>{code}</b>', f'Apply code <b>{code}</b> at checkout', f'Promo code <b>{code}</b>')
    exp = ''
    if expiry_t:
        exp = alt(f'Offer valid till {fmt_date(expiry_t)}', f'Valid until {fmt_date(expiry_t)}', f'Expires on {fmt_date(expiry_t, "d mon")}',
                  f'Offer ends {fmt_date(expiry_t)}')
    tnc = alt('T&C apply.', 'Min order applicable. T&C apply.', 'Terms and conditions apply.')
    return page(card([shop, off, head, exp, tnc], title=alt('You won a reward!', 'Special offer for you', 'Scratch card', 'Exclusive deal'), color='#fff3e0'), bg='#fafafa')


def delivery(status, date_t):
    item = R.choice(['boAt Airdopes 141', 'Prestige Pressure Cooker 5L', 'Levi\'s Men Jeans', 'Philips Trimmer', 'Milton Water Bottle', 'USB-C Cable 2m'])
    oid = f'{R.randint(400, 408)}-{R.randint(1000000, 9999999)}-{R.randint(1000000, 9999999)}'
    shop = R.choice(['Amazon', 'Flipkart', 'Meesho', 'Myntra'])
    if status == 'delivered':
        head = alt(f'Delivered on {fmt_date(date_t, "d mon")}', f'Your package was delivered {fmt_date(date_t)}', f'Delivered {fmt_date(date_t)}',
                   f'Package delivered on {fmt_date(date_t)}')
        sub = alt('Return window closes 7 days after delivery', 'Rate your experience', 'Package was handed to resident')
    else:
        head = alt('Out for delivery', 'Arriving today by 9 PM', 'Arriving tomorrow', 'Shipped: arriving soon')
        sub = alt('Track package', 'Your order is on the way with our courier partner', 'Expected delivery by 9 PM')
    return page(card([f'<b>{shop}</b>', head, item, f'Order # {oid}', sub, alt('Track order', 'View order details', 'Need help?')],
                     title=alt('Your Orders', 'Order details', 'Track shipment')), bg='#f1f3f4')


def ride():
    if R.random() < 0.5:
        app = R.choice(['Uber', 'Ola', 'Rapido'])
        lines = [alt(f'Your ride is arriving in {R.randint(2, 9)} mins', f'Driver is {R.randint(1, 6)} min away', f'Your {app} ride is on the way',
                     f'Pickup in {R.randint(2, 8)} min'),
                 f'Driver {R.choice(NAMES).split()[0]} • {R.choice(["Swift Dzire", "WagonR", "Honda Activa", "Bajaj Auto"])}',
                 f'KA {R.randint(1, 60):02d} {R.choice("ABCDM")}{R.choice("ABCDM")} {R.randint(1000, 9999)}',
                 f'Ride OTP {R.randint(1000, 9999)}', alt('Pickup: Indiranagar Metro', 'Drop: Koramangala 5th Block', 'Share ride details'), app]
    else:
        app = R.choice(['Swiggy', 'Zomato'])
        lines = [alt('Your order is on the way', 'Food is being prepared', 'Delivery partner is on the way', 'Order picked up'),
                 f'{R.choice(["Meghana Foods", "Truffles", "Empire Restaurant", "Burger King"])}',
                 f'Arriving in {R.randint(8, 35)} mins', f'Delivery partner {R.choice(NAMES).split()[0]}', app]
    return page(card(lines, color='#e8f5e9'), bg='#fff')


def maps():
    place = R.choice(['Kempegowda Airport', 'Phoenix Marketcity', 'MG Road Metro', 'Lalbagh', 'Majestic Bus Stand', 'Manipal Hospital'])
    km, mins = R.randint(3, 40), R.randint(10, 95)
    return page(f'''<div style="height:420px;background:linear-gradient(135deg,#d6e9c6,#f5f5dc 40%,#cfe2f3);position:relative">
      <div style="position:absolute;left:40px;top:300px;width:280px;height:6px;background:#4285f4;transform:rotate(-30deg)"></div></div>
      {card([f"<b>{mins} min</b> ({km} km)", alt(f"Fastest route now due to traffic conditions", f"via Outer Ring Road", f"Best route, usual traffic", f"via NH 44"),
             f"Your location → {place}", alt("Start", "Steps", "Directions", "Start navigation")], title=alt("Directions", "Google Maps", "Route"))}''')


def weather():
    city = R.choice(['Bengaluru', 'Mumbai', 'Chennai', 'Pune', 'Hyderabad', 'Delhi'])
    t = R.randint(18, 38)
    return page(card([f'<span style="font-size:60px">{t}°C</span>', alt('Partly cloudy', 'Sunny', 'Light rain', 'Thunderstorm'),
                      f'Feels like {t + R.randint(-2, 3)}°', f'Humidity {R.randint(40, 95)}%', alt(f'Chance of rain {R.randint(10, 90)}%', f'Precipitation {R.randint(10, 90)}%'),
                      alt('Hourly forecast', '10-day forecast', 'UV index moderate', 'Air quality 62 Moderate')], title=city, color='#e3f2fd'), bg='#bbdefb')


def parking():
    return page(card([alt('PARKING TICKET', 'Parking receipt', 'Valet parking', 'Your token'), f'Token no {R.randint(100, 9999)}',
                      f'Vehicle no KA{R.randint(1, 60):02d}MJ{R.randint(1000, 9999)}', f'In time {R.randint(1, 12)}:{R.randint(10, 59)} PM',
                      alt('Level B2, Slot 44', 'Please keep this token safe', 'Entry gate 2', 'Lost ticket charges apply'),
                      R.choice(['Orion Mall', 'Forum Mall', 'Airport P2', 'Inorbit Mall'])], color='#fffde7'), bg='#fff')


def shopping():
    item = R.choice(['boAt Rockerz 450 Bluetooth Headphones', 'Nike Revolution 6 Running Shoes', 'Redmi Note 13 5G (8GB)', 'Wooden Study Table',
                     'Samsung 7kg Washing Machine', 'Men Cotton Kurta'])
    p, pt = rupees(499, 24999)
    mrp = int(p * R.uniform(1.4, 3))
    return page(f'''<div style="height:500px;background:linear-gradient(#eee,#ccc)"></div>
      <div style="padding:16px;font-size:17px;line-height:1.8"><b>{item}</b><br>★ 4.{R.randint(0, 6)} | {R.randint(200, 90000):,} ratings<br>
      <span style="font-size:28px">₹{pt}</span> &nbsp; M.R.P.: <s>₹{mrp:,}</s> ({100 - p * 100 // mrp}% off)<br>
      {alt("Inclusive of all taxes", "Free delivery by Tomorrow", "No Cost EMI available", "Bank Offer: 10% instant discount")}<br>
      {alt("In stock", "Only 3 left in stock", "Select size", "Size chart")}<br>
      <div style="margin-top:12px;padding:12px;background:#ffd814;border-radius:20px;text-align:center">{alt("Add to Cart", "Add to Bag", "Add to cart")}</div>
      <div style="margin-top:8px;padding:12px;background:#ffa41c;border-radius:20px;text-align:center">{alt("Buy Now", "Buy now", "Buy Now")}</div></div>''')


def ticket(journey_t, cancelled=False):
    k = R.randrange(3)
    status = 'Booking Status: CAN / Refund of ₹' + f'{R.randint(300, 3000):,}' + ' initiated' if cancelled else 'Booking Status: CNF'
    if k == 0:
        return page(card([f'PNR: {R.randint(1000000000, 9999999999)}', f'Train No./Name: {R.randint(10000, 22999)} / {R.choice(["RAJDHANI EXP", "SHATABDI EXP", "VANDE BHARAT"])}',
                          f'Date of Journey: {fmt_date(journey_t, "d-mon-y")}', f'From: {R.choice(["SBC", "MAS", "NDLS"])} To: {R.choice(["HWH", "PUNE", "BCT"])}',
                          f'Coach: B{R.randint(1, 9)} Berth: {R.randint(1, 72)}', status], title='IRCTC e-Ticket'))
    if k == 1:
        return page(card([R.choice(['IndiGo', 'Air India', 'Akasa Air']), f'Flight {R.choice(["6E", "AI", "QP"])} {R.randint(100, 999)}', 'From BLR To DEL',
                          f'Date {fmt_date(journey_t, "d mon y")}', f'Gate {R.randint(1, 40)} Seat {R.randint(1, 32)}{R.choice("ABCDEF")}',
                          f'Boarding {R.randint(5, 22)}:{R.randint(10, 59)}', 'Cancelled. Refund initiated' if cancelled else f'Sequence {R.randint(1, 180)}'],
                         title='BOARDING PASS', color='#fff8e1'))
    return page(card([R.choice(['Jawan', 'Kalki 2898 AD', 'Pushpa 2', 'Stree 2']) + ' (UA)', f'{R.choice(["PVR", "INOX", "Cinepolis"])}: {R.choice(["Phoenix Mall", "Forum", "Orion"])}',
                      f'{fmt_date(journey_t, "d mon y")} | {R.randint(1, 11)}:{R.randint(10, 59)} PM', f'Screen {R.randint(1, 8)} Seats: {R.choice("ABCDEFGH")}{R.randint(1, 20)}',
                      f'Booking ID: {R.choice("ABCDEFG")}{R.randint(100000, 999999)}', 'Cancelled, refund initiated' if cancelled else 'Ticket(s): 2'], title='Booking Confirmed'))


def appointment(date_t):
    k = R.randrange(2)
    if k == 0:
        return page(card([alt('Appointment confirmed', 'Your appointment is booked', 'Appointment reminder', 'Consultation scheduled'),
                          f'Dr. {R.choice(NAMES)} ({R.choice(["Dermatologist", "Dentist", "General Physician"])})',
                          f'{R.choice(["Apollo Clinic", "Practo Care", "Cloudnine"])}, {R.choice(["Indiranagar", "HSR Layout", "Whitefield"])}',
                          f'Date: {fmt_date(date_t)}', f'Time: {R.randint(9, 12)}:{R.choice(["00", "30"])} AM', alt('Reschedule', 'Cancel appointment')],
                         color='#ede7f6'))
    return page(card([alt('Table reservation confirmed', 'Your booking is confirmed', 'Reservation confirmed', 'Table booked'),
                      f'{R.choice(["Toit", "Karavalli", "The Fatty Bao", "Truffles"])}', f'Table for {R.randint(2, 8)} guests',
                      f'{fmt_date(date_t)} at {R.randint(7, 10)}:{R.choice(["00", "30"])} PM', alt('Booking ID: DN' + str(R.randint(10000, 99999)), 'Show this at the venue')],
                     color='#fce4ec'))


def chat():
    lines = ['Hi, are you coming today?', 'Yes, will reach by 7', 'ok see you', 'Did you send the photos?', 'Sending now', 'Thanks!',
             'Call me when free', 'What time is the meeting tomorrow?', '10 am I think', 'Reached home', 'Send me the address', 'Okay']
    msgs = ''.join(f'<div style="max-width:70%;margin:6px 10px;padding:8px 12px;border-radius:10px;font-size:16px;{"margin-left:auto;background:#dcf8c6" if R.random() < 0.5 else "background:#fff"}">'
                   f'{R.choice(lines)}<span style="font-size:11px;color:#888"> {R.randint(1, 12)}:{R.randint(10, 59)} pm</span></div>' for _ in range(R.randint(8, 13)))
    return page(f'<div style="background:#075e54;color:#fff;padding:14px;font-size:19px">{R.choice(NAMES)}<div style="font-size:12px">online</div></div>{msgs}'
                '<div style="position:fixed;bottom:10px;left:10px;right:10px;background:#fff;border-radius:20px;padding:10px;color:#888">Type a message</div>', bg='#ece5dd')


def article():
    para = ' '.join(R.choice(['The monsoon session of parliament began on Monday.', 'Markets closed higher as banking stocks rallied.',
                              'Scientists say new telescope images reveal distant galaxies.', 'The city will add 40 new electric buses this year.',
                              'Experts recommend drinking enough water in summer.', 'The match was delayed due to rain in the evening.']) for _ in range(12))
    return page(f'<div style="padding:18px;font-size:24px;font-weight:bold">{R.choice(["City news", "Tech today", "Weekend reads"])}</div>'
                f'<div style="padding:0 18px;font-size:17px;line-height:1.6">{para}</div>')


def wifi():
    net = R.choice(['Airtel_Ravi', 'JioFiber-5G-Home', 'ACT_Meena', 'Office_Guest', 'TP-Link_4F2A'])
    pw = R.choice(['ravi@1234', 'Sunshine#2024', 'meena9845', 'guest@office', 'Tplink!889'])
    return page(card([alt(f'Wi-Fi: {net}', f'Network name: {net}', f'WiFi {net}', f'SSID: {net}'), f'Password: {pw}', alt('Security WPA2', 'Share Wi-Fi', 'Connected')],
                     title=alt('Wi-Fi details', 'Home WiFi', 'Network details', 'Wi-Fi password')))


def bank_details():
    n = R.choice(NAMES)
    return page(card([f'Account holder: {n}', f'Account number: {R.randint(10 ** 13, 10 ** 14 - 1)}', f'IFSC: {R.choice(["HDFC", "SBIN", "ICIC", "UTIB"])}000{R.randint(1000, 9999)}',
                      f'Branch: {R.choice(["Indiranagar", "Jayanagar", "Andheri West", "T Nagar"])}', alt('Savings account', 'Please transfer the advance here')],
                     title=alt('Bank details', 'Account details', 'Payment details', 'Beneficiary details')))


def prescription():
    meds = R.sample(['Tab. Paracetamol 500 mg', 'Cap. Amoxicillin 250 mg', 'Tab. Cetirizine 10 mg', 'Syrup Benadryl 5 ml', 'Tab. Pantoprazole 40 mg', 'Tab. Vitamin D3 60k'], 3)
    head = alt('Rx', 'Prescription', 'Rx', 'Prescription')
    return f'''<html><body style="margin:0;background:#fff;font-family:Georgia,serif;padding:40px;font-size:30px;line-height:1.7">
      <div style="font-size:40px;font-weight:bold">Dr. {R.choice(NAMES)} MBBS, MD</div><div>{R.choice(["City Clinic", "Sunrise Hospital", "Apollo Clinic"])} • Reg. No. {R.randint(10000, 99999)}</div><hr>
      Patient: {R.choice(NAMES)} &nbsp; Age/Sex: {R.randint(5, 80)}/{R.choice("MF")}<br>Date: {fmt_date(when(R.randint(100, 900)))}<br>
      <div style="font-size:44px;margin:20px 0">{head}</div>{"<br>".join(m + " - " + R.choice(["twice daily after food", "once daily before food", "at night"]) for m in meds)}
      <br><br>Diagnosis: {R.choice(["Viral fever", "Upper respiratory infection", "Gastritis", "Allergic rhinitis"])}<br>Review after 5 days</body></html>'''


def lab_report():
    rows = ''.join(f'<tr><td>{t}</td><td>{v}</td><td>{u}</td><td>{rr}</td></tr>' for t, v, u, rr in
                   [('Haemoglobin', round(R.uniform(10, 16), 1), 'g/dL', '13.0 - 17.0'), ('Fasting Blood Sugar', R.randint(80, 160), 'mg/dL', '70 - 100'),
                    ('Total Cholesterol', R.randint(140, 260), 'mg/dL', '< 200'), ('TSH', round(R.uniform(0.5, 6), 2), 'uIU/mL', '0.4 - 4.0')])
    return f'''<html><body style="margin:0;background:#fff;font-family:Arial;padding:40px;font-size:28px;line-height:1.6">
      <div style="font-size:38px;font-weight:bold">{R.choice(["Thyrocare", "Dr Lal PathLabs", "Metropolis Healthcare"])}</div>
      <div>{alt("Laboratory Test Report", "Test Report", "Lab Report", "Pathology Report")}</div><hr>Patient Name: {R.choice(NAMES)} &nbsp; Age/Sex: {R.randint(20, 70)}/M<br>
      Sample collected: {fmt_date(when(R.randint(100, 900)))}<br><table style="font-size:26px;margin-top:20px" cellpadding="8">
      <tr><th>Test</th><th>Result</th><th>Units</th><th>Reference Range</th></tr>{rows}</table></body></html>'''


def warranty():
    item = R.choice(['Samsung Refrigerator RT28', 'LG Washing Machine FHM1207', 'Voltas 1.5T Split AC', 'Havells Water Heater', 'Sony Bravia 43"'])
    return f'''<html><body style="margin:0;background:#fffef5;font-family:Arial;padding:40px;font-size:30px;line-height:1.8;border:8px double #333">
      <div style="font-size:44px;font-weight:bold;text-align:center">{alt("WARRANTY CARD", "Warranty Certificate", "Guarantee Card", "WARRANTY CARD")}</div>
      Product: {item}<br>Model No: {R.choice("ABCDEFGH")}{R.randint(100, 999)}-{R.randint(10, 99)}<br>Serial No: {R.choice("RSX")}{R.randint(10 ** 9, 10 ** 10 - 1)}<br>
      Date of Purchase: {fmt_date(when(R.randint(200, 1200)))}<br>Dealer: {R.choice(["Croma", "Reliance Digital", "Vijay Sales", "Girias"])}<br>
      {alt("Warranty: 2 years from the date of purchase", "Extended warranty: 5 years on compressor", "Keep this card for service")}<br>
      Customer care: 1800 {R.randint(100, 999)} {R.randint(1000, 9999)}</body></html>'''


def insurance():
    return page(card([R.choice(['Star Health Insurance', 'HDFC ERGO', 'LIC of India', 'ICICI Lombard']), f'Policy number: {R.randint(10 ** 9, 10 ** 10 - 1)}',
                      f'Sum insured: ₹{R.choice(["5,00,000", "10,00,000", "3,00,000"])}', f'Policyholder: {R.choice(NAMES)}', f'Nominee: {R.choice(NAMES)}',
                      alt(f'Premium due: {fmt_date(when(-R.randint(10, 200)))}', f'Renewal date: {fmt_date(when(-R.randint(10, 200)))}')],
                     title=alt('Policy details', 'Your policy', 'Health insurance', 'Motor insurance')))


def certificate():
    k = R.randrange(3)
    if k == 0:
        return f'''<html><body style="margin:0;background:#fff;font-family:Georgia;padding:60px;font-size:32px;line-height:1.8;text-align:center;border:12px double #8a6d3b">
          <div style="font-size:54px">{alt("Certificate of Completion", "Certificate of Achievement", "Certificate", "Certificate of Merit")}</div>
          This is to certify that<br><b>{R.choice(NAMES)}</b><br>has successfully completed the course<br><i>{R.choice(["Data Analysis with Python", "First Aid", "Advanced Excel"])}</i><br>
          awarded on {fmt_date(when(R.randint(300, 2000)))}<br><br>Signature</body></html>'''
    if k == 1:
        rows = ''.join(f'<tr><td>{a}</td><td>{R.randint(20000, 90000):,}</td><td>{b}</td><td>{R.randint(200, 9000):,}</td></tr>' for a, b in
                       [('Basic', 'Provident Fund'), ('HRA', 'Professional Tax'), ('Special allowance', 'Income Tax')])
        return f'''<html><body style="margin:0;background:#fff;font-family:Arial;padding:40px;font-size:26px;line-height:1.6">
          <div style="font-size:36px;font-weight:bold">{R.choice(["Infosys Ltd", "Wipro Ltd", "Acme Software Pvt Ltd"])}</div><div>{alt("Salary slip", "Payslip", "Pay slip")} for {R.choice(MON)} {R.choice([2024, 2025])}</div>
          Employee: {R.choice(NAMES)} &nbsp; Employee ID: E{R.randint(10000, 99999)} &nbsp; UAN: {R.randint(10 ** 11, 10 ** 12 - 1)}
          <table cellpadding="8" style="font-size:24px"><tr><th>Earnings</th><th>Amount</th><th>Deductions</th><th>Amount</th></tr>{rows}</table>
          Net pay: ₹{R.randint(40000, 150000):,}</body></html>'''
    return f'''<html><body style="margin:0;background:#fff;font-family:Arial;padding:40px;font-size:28px;line-height:1.7">
      <div style="font-size:40px;font-weight:bold;text-align:center">{alt("RENTAL AGREEMENT", "Rent Agreement", "Lease Agreement", "RENTAL AGREEMENT")}</div>
      This agreement is made on {fmt_date(when(R.randint(200, 900)))} between {R.choice(NAMES)} (Landlord) and {R.choice(NAMES)} (Tenant)
      for the premises at {R.randint(1, 99)}, {R.choice(["4th Cross", "MG Road", "2nd Main"])}, Bengaluru. The monthly rent is ₹{R.randint(10, 40)},000 and the security deposit is ₹{R.randint(1, 3)},00,000.
      The tenancy is for 11 months. Witness 1, Witness 2. Signature of Landlord, Signature of Tenant.</body></html>'''


def bill():
    p, pt = rupees(400, 6000)
    return f'''<html><body style="margin:0;background:#fff;font-family:Arial;padding:40px;font-size:28px;line-height:1.7">
      <div style="font-size:40px;font-weight:bold">{R.choice(["BESCOM", "Airtel", "Jio", "BWSSB", "Tata Power"])}</div>
      <div>{alt("Electricity Bill", "Tax Invoice", "Bill of Supply", "Monthly Bill")}</div><hr>Consumer No: {R.randint(10 ** 6, 10 ** 7)}<br>
      Bill date: {fmt_date(when(R.randint(100, 900)))}<br>Billing period: {R.choice(MON)} {R.choice([2024, 2025])}<br>Units consumed: {R.randint(80, 400)}<br>
      Amount due: ₹{pt}.00<br>Due date: {fmt_date(when(R.randint(80, 880)))}<br>GSTIN: 29ABCDE{R.randint(1000, 9999)}F1Z5</body></html>'''


def id_card():
    return f'''<html><body style="margin:0;background:#fff;font-family:Arial;padding:30px;font-size:30px;line-height:1.6">
      <div style="background:#f4a261;padding:10px;font-weight:bold">{alt("Government of India", "GOVT. OF INDIA")}</div>
      {R.choice(NAMES)}<br>DOB: {R.randint(1, 28):02d}/{R.randint(1, 12):02d}/{R.randint(1960, 2005)}<br>{R.choice(["Male", "Female"])}<br>
      <div style="font-size:44px;margin-top:20px">{R.randint(2000, 9999)} {R.randint(1000, 9999)} {R.randint(1000, 9999)}</div>
      <div style="background:#2a9d8f;color:#fff;padding:8px;margin-top:20px">Aadhaar - Mera Aadhaar, Meri Pehchaan</div></body></html>'''


def receipt(total, warranty_line=False):
    items = [(R.choice(['Milk 1L', 'Bread', 'Eggs 12', 'Rice 5kg', 'Sugar 1kg', 'Masala Dosa', 'Filter Coffee', 'Paneer 200g', 'Biscuits']), 0) for _ in range(R.randint(3, 7))]
    n = len(items); parts = [round(total / n * R.uniform(0.6, 1.4), 2) for _ in range(n)]
    s = sum(parts); parts = [round(p * total / s, 2) for p in parts]
    shop = R.choice(['SRI KRISHNA SWEETS', 'MORE SUPERMARKET', 'NANDHINI DELUXE', 'RELIANCE DIGITAL', 'CROMA', 'CAFE COFFEE DAY'])
    rows = ''.join(f'<div>{name:<20} {p:,.2f}</div>' for (name, _), p in zip(items, parts))
    w = f'<div>IMEI: {R.randint(10 ** 14, 10 ** 15 - 1)}</div><div>Warranty: 1 year</div>' if warranty_line else ''
    pay = alt(f'Cash {math.ceil(total / 100) * 100:,.2f}<br>Change {math.ceil(total / 100) * 100 - total:,.2f}', 'Paid by card', f'UPI {total:,.2f}')
    return f'''<html><body style="margin:0;background:#fff;font-family:'Courier New',monospace;width:420px;padding:20px;font-size:20px;line-height:1.5">
      <div style="text-align:center;font-weight:bold">{shop}</div><div style="text-align:center">Bill No {R.randint(100, 9999)} Cashier {R.choice(NAMES).split()[0]}</div><hr>
      {rows}{w}<hr><div>Sub Total {total / 1.05:,.2f}</div><div>CGST 2.5% {total * 0.025 / 1.05:,.2f}</div><div>SGST 2.5% {total * 0.025 / 1.05:,.2f}</div>
      <div style="font-weight:bold">TOTAL {total:,.2f}</div><div>{pay}</div><div style="text-align:center">Thank you, come again</div></body></html>'''


def invitation(date_t):
    return f'''<html><body style="margin:0;background:radial-gradient(#fff3cd,#e9b949);font-family:Georgia;padding:60px;font-size:34px;line-height:1.8;text-align:center">
      <div style="font-size:30px">{alt("With the blessings of our elders", "Together with their families", "Save the date")}</div>
      <div style="font-size:50px;color:#8b0000">{R.choice(NAMES).split()[0]} &amp; {R.choice(NAMES).split()[0]}</div>
      {alt("cordially invite you to their wedding", "request the pleasure of your company at their wedding reception", "invite you to celebrate their marriage")}<br>
      on {fmt_date(date_t, "d mon y")}<br>at {R.choice(["Palace Grounds", "Sri Krishna Convention Hall", "Taj West End"])}, Bengaluru</body></html>'''


def contact():
    return page(card([f'{R.choice(NAMES)}', f'Address: #{R.randint(1, 300)}, {R.randint(1, 18)}th Cross, {R.choice(["Indiranagar", "Jayanagar 4th Block", "HSR Layout"])}',
                      f'Bengaluru {R.choice(["560038", "560041", "560102"])}', f'Landmark: {alt("near Ganesha temple", "opp. SBI ATM", "behind the bus stop")}',
                      f'Mobile: 9{R.randint(100000000, 999999999)}'], title=alt('Address', 'Location shared', 'Delivery address', 'Contact')))


# ------------------------------------------------------------------ chat images and photos (Pillow)
def scene(w=1600, h=1200, dark=False):
    """A plain outdoor-like photo: sky, ground, blobs, noise. Not meant to fool anyone, only to carry no text."""
    a = np.zeros((h, w, 3), np.float32)
    top, bot = np.array([R.randint(90, 160), R.randint(140, 200), R.randint(200, 250)]), np.array([R.randint(60, 120), R.randint(90, 150), R.randint(40, 90)])
    hor = R.randint(h // 3, 2 * h // 3)
    for y in range(h):
        a[y] = top + (bot - top) * (0 if y < hor else 1) + (y / h) * 20
    im = Image.fromarray(np.clip(a, 0, 255).astype(np.uint8)); d = ImageDraw.Draw(im)
    for _ in range(R.randint(6, 18)):
        x, y, r = R.randint(0, w), R.randint(hor - 200, h), R.randint(30, 220)
        d.ellipse([x - r, y - r, x + r, y + r], fill=(R.randint(20, 200), R.randint(60, 200), R.randint(20, 150)))
    im = im.filter(ImageFilter.GaussianBlur(R.uniform(0.6, 1.2)))   # about as sharp as a phone photo
    n = np.array(im).astype(np.float32) + np.random.default_rng(R.randint(0, 10 ** 6)).normal(0, 6, (h, w, 3))
    if dark: n = n * 0.06
    return Image.fromarray(np.clip(n, 0, 255).astype(np.uint8))


def text_on_picture(lines, colors=('#fff', '#ffeb3b'), w=1080, h=1080):
    bg = f'linear-gradient({R.randint(0, 360)}deg,{R.choice(["#ff7043", "#7e57c2", "#26a69a", "#ec407a", "#5c6bc0"])},{R.choice(["#ffca28", "#29b6f6", "#9ccc65", "#8d6e63"])})'
    body = ''.join(f'<div style="color:{R.choice(colors)};margin:24px">{l}</div>' for l in lines)
    return (f'<html><body style="margin:0;width:{w}px;height:{h}px;background:{bg};display:flex;flex-direction:column;justify-content:center;'
            f'text-align:center;font:bold 74px Georgia,serif;text-shadow:3px 3px 6px #000">{body}</body></html>'), (w, h)


def greeting():
    return text_on_picture([alt('Good Morning', 'Happy Diwali', 'Good Night', 'Happy Sunday', 'Happy Navratri', 'Good Evening'),
                            alt('Have a blessed day', 'Stay blessed', 'Wishing you joy and happiness', 'Sweet dreams')])


def meme():
    top = alt('When you check your bank balance', 'Me after eating one salad', 'When the boss says quick call', 'Nobody: Me at 3 am',
              'When mom says we have food at home', 'Me explaining to my wife')
    bot = alt('after the weekend lol', 'expecting abs', 'and it takes 2 hours', 'opening the fridge again', 'funny but true', 'share with friends')
    lines = [top, bot]
    if R.random() < 0.5: lines.append(alt('Forwarded many times', 'Share this with your friends', 'Forwarded', 'Send this to 5 people'))
    return text_on_picture(lines, colors=('#fff',))


def as_photo(png_bytes):
    """Paper photographed on a table: rotated a little, uneven light, slight blur, JPEG."""
    pg = Image.open(io.BytesIO(png_bytes)).convert('RGB')
    bg = scene(2000, 1500).filter(ImageFilter.GaussianBlur(8))
    s = min(1700 / pg.width, 1350 / pg.height) * R.uniform(0.85, 1.0)
    pg = pg.resize((int(pg.width * s), int(pg.height * s))).rotate(R.uniform(-4, 4), expand=True, fillcolor=(90, 80, 70))
    bg.paste(pg, ((2000 - pg.width) // 2, (1500 - pg.height) // 2))
    a = np.array(bg).astype(np.float32) * np.linspace(R.uniform(0.75, 1.0), R.uniform(0.95, 1.1), 2000)[None, :, None]
    return Image.fromarray(np.clip(a, 0, 255).astype(np.uint8)).filter(ImageFilter.GaussianBlur(R.uniform(0.3, 0.8)))


# ------------------------------------------------------------------ the phone
counter = [0]


def save(img_or_html, rel_dir, label, kind, age_days, ext=None, faces=None, photo=False, size=None):
    counter[0] += 1
    i = counter[0]
    t = when(age_days)
    import time
    g = time.gmtime(t); stamp = f'{g.tm_year}{g.tm_mon:02d}{g.tm_mday:02d}'
    if rel_dir.startswith('WhatsApp'): name = f'IMG-{stamp}-WA{i:04d}.jpg'
    elif 'Screenshots' in rel_dir: name = f'Screenshot_{stamp}-{i:04d}.png'
    elif rel_dir.startswith('DCIM'): name = f'IMG_{stamp}_{i:04d}.jpg'
    else: name = f'{kind}_{i:04d}.' + (ext or 'png')
    rel = f'{rel_dir}/{name}'; path = os.path.join(OUT, 'sdcard', rel)
    os.makedirs(os.path.dirname(path), exist_ok=True)
    if isinstance(img_or_html, Image.Image): im = img_or_html
    else:
        html = img_or_html
        vp = size or (360, 800)
        pg.set_viewport_size({'width': vp[0], 'height': vp[1]})
        pg.set_content(html)
        shot = pg.screenshot(full_page=False)
        im = as_photo(shot) if photo else Image.open(io.BytesIO(shot))
    if name.endswith('.jpg'): im.convert('RGB').save(path, quality=88)
    else: im.save(path)
    if faces:
        with open(path + '.faces', 'w') as f:
            for k in range(faces): f.write(f'{0.25 + 0.2 * k} 0.4 0.06 0.9\n')
    os.utime(path, (t, t))
    labels[rel] = {'need': label, 'kind': kind, 'age': round(age_days, 2)}


SHOTS = 'Pictures/Screenshots'; WA = 'WhatsApp/Media/WhatsApp Images'; CAM = 'DCIM/Camera'
with sync_playwright() as p:
    b = p.chromium.launch(); pg = b.new_page(viewport={'width': 360, 'height': 800}, device_scale_factor=3)
    N = 8
    for _ in range(N):
        # ---------------- no longer needed
        save(otp(), SHOTS, 'delete', 'otp_old', R.uniform(2, 120))
        a = R.uniform(25, 120); save(coupon(when(a - R.uniform(4, 20))), SHOTS, 'delete', 'coupon_expired', a)
        save(coupon(None), SHOTS, 'delete', 'coupon_old', R.uniform(80, 300))
        a = R.uniform(45, 300); save(delivery('delivered', when(a - R.uniform(0, 2))), SHOTS, 'delete', 'delivery_old', a)
        save(delivery('transit', 0), SHOTS, 'delete', 'delivery_status_old', R.uniform(50, 300))
        save(ride(), SHOTS, 'delete', 'ride_old', R.uniform(35, 300))
        save(maps(), SHOTS, 'delete', 'maps_old', R.uniform(30, 300))
        save(weather(), SHOTS, 'delete', 'weather_old', R.uniform(10, 300))
        save(parking(), SHOTS, 'delete', 'parking_old', R.uniform(10, 300))
        save(shopping(), SHOTS, 'delete', 'shopping_old', R.uniform(110, 400))
        v, vt = rupees(20, 1500); save(payment(vt, R.choice(NAMES)), SHOTS, 'delete', 'payment_small_old', R.uniform(130, 700))
        v, vt = rupees(50, 5000); save(payment(vt, R.choice(NAMES), 'failed'), SHOTS, 'delete', 'payment_failed_old', R.uniform(30, 300))
        a = R.uniform(20, 300); save(ticket(when(R.uniform(12, a - 1))), SHOTS, 'delete', 'ticket_past', a)
        a = R.uniform(20, 200); save(appointment(when(R.uniform(14, a - 1))), SHOTS, 'delete', 'appointment_past', a)
        html, sz = greeting(); save(html, WA, 'delete', 'greeting_old', R.uniform(15, 300), size=sz)
        html, sz = meme(); save(html, WA, 'delete', 'meme_old', R.uniform(50, 300), size=sz)
        save(receipt(round(R.uniform(60, 900), 2)), CAM, 'delete', 'receipt_small_old', R.uniform(330, 900), photo=True, size=(460, 900))
        save(scene(dark=True), CAM, 'delete', 'accidental', R.uniform(1, 900))
        # ---------------- still needed (or not safe to suggest)
        save(otp(), SHOTS, 'keep', 'otp_fresh', R.uniform(0.02, 0.6))
        a = R.uniform(2, 30); save(coupon(when(-R.uniform(5, 60))), SHOTS, 'keep', 'coupon_valid', a)
        save(delivery('transit', 0), SHOTS, 'keep', 'delivery_recent', R.uniform(0.2, 8))
        a = R.uniform(1, 40); save(ticket(when(-R.uniform(2, 60))), SHOTS, 'keep', 'ticket_upcoming', a)
        a = R.uniform(5, 30); save(ticket(when(a - 2), cancelled=True), SHOTS, 'keep', 'ticket_refund', a)
        a = R.uniform(1, 20); save(appointment(when(-R.uniform(2, 30))), SHOTS, 'keep', 'appointment_upcoming', a)
        v, vt = rupees(20, 1500); save(payment(vt, R.choice(NAMES)), SHOTS, 'keep', 'payment_recent', R.uniform(1, 40))
        v, vt = rupees(8000, 40000); save(payment(vt, R.choice(NAMES), purpose=alt('House rent', 'School fees', 'Rent for October', 'Tuition fee', 'Security deposit', 'Loan EMI')),
                                         SHOTS, 'keep', 'payment_purpose', R.uniform(150, 900))
        v, vt = rupees(15000, 250000); save(payment(vt, R.choice(NAMES)), SHOTS, 'keep', 'payment_large', R.uniform(150, 900))
        v, vt = rupees(100, 5000); save(payment(vt, R.choice(NAMES), 'pending'), SHOTS, 'keep', 'payment_pending', R.uniform(3, 30))
        v, vt = rupees(20, 1500); save(payment(vt, R.choice(NAMES)), 'Pictures/' + alt('Important', 'Tax 2025', 'Bills', 'Keep'), 'keep', 'payment_in_folder', R.uniform(200, 700))
        save(warranty(), CAM, 'keep', 'warranty', R.uniform(200, 1200), photo=True, size=(600, 850))
        save(prescription(), WA if R.random() < 0.5 else CAM, 'keep', 'prescription', R.uniform(100, 900), photo=True, size=(600, 850))
        save(lab_report(), 'Download', 'keep', 'lab_report', R.uniform(100, 900), size=(800, 1100))
        save(insurance(), SHOTS, 'keep', 'insurance', R.uniform(100, 900))
        save(certificate(), 'Download', 'keep', 'record', R.uniform(200, 1500), size=(1100, 800) if R.random() < 0.3 else (800, 1100))
        save(wifi(), SHOTS, 'keep', 'wifi', R.uniform(100, 1500))
        save(bank_details(), WA if R.random() < 0.5 else SHOTS, 'keep', 'bank_details', R.uniform(100, 1500))
        save(id_card(), 'Download', 'keep', 'id', R.uniform(100, 1500), size=(800, 520))
        save(bill(), 'Download', 'keep', 'bill', R.uniform(100, 900), size=(800, 1100))
        save(receipt(round(R.uniform(12000, 60000), 2), warranty_line=R.random() < 0.5), CAM, 'keep', 'receipt_large', R.uniform(330, 1200), photo=True, size=(460, 1000))
        save(chat(), SHOTS, 'keep', 'chat', R.uniform(100, 1500))
        save(article(), SHOTS, 'keep', 'article', R.uniform(100, 1500))
        save(contact(), SHOTS, 'keep', 'contact', R.uniform(100, 1500))
        save(invitation(when(-R.uniform(5, 90))), WA, 'keep', 'invitation_upcoming', R.uniform(2, 30), size=(800, 1100))
        save(scene(), CAM, 'keep', 'photo', R.uniform(1, 2000))
        save(scene(), CAM, 'keep', 'people', R.uniform(1, 2000), faces=R.randint(1, 4))
        save(scene(), WA, 'keep', 'people_chat', R.uniform(30, 1000), faces=R.randint(1, 3))
    b.close()
os.makedirs(os.path.join(OUT, 'meta'), exist_ok=True)
open(os.path.join(OUT, 'meta', 'usagestats.json'), 'w').write('{}')
json.dump(labels, open(os.path.join(OUT, 'labels.json'), 'w'), indent=0)
print(SET, len(labels), 'pictures,', sum(1 for v in labels.values() if v['need'] == 'delete'), 'not needed')
