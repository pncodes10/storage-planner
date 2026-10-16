"""Synthetic phone screenshots (rendered in Chromium at 1080x2400): UPI payments, one-time codes,
tickets and boarding passes, chats, and ordinary text-heavy screens (settings, articles) as negatives."""
import asyncio, json, os, random
from playwright.async_api import async_playwright
R = random.Random(5); OUT = '/tmp/claude-0/corpus'; AREA = 'Pictures/Screenshots'
names = ['Ramesh Kumar', 'Priya Sharma', 'Anil Traders', 'Sri Balaji Stores', 'Meena Patel', 'Zomato', 'Swiggy', 'Airtel Prepaid', 'Fresh Mart', 'Rahul Verma']
months = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec']
def date(): return f"{R.randint(1,28)} {R.choice(months)} {R.choice([2023,2024,2025,2026])}"
def amt(): return f"{R.randint(20, 9000):,}" + R.choice(['', '.00'])
STATUS = '<div style="height:28px;background:#111;color:#fff;font:12px sans-serif;padding:6px 14px">9:41 &nbsp; ▲ ◉ 87%</div>'
def page(body, bg='#fff'): return f'<html><body style="margin:0;background:{bg};font-family:Roboto,Arial,sans-serif">{STATUS}{body}</body></html>'
def payment(i):
    app = ['Google Pay', 'PhonePe', 'Paytm', 'BHIM'][i % 4]; n = R.choice(names); a = amt(); upi = ''.join(R.choice('0123456789') for _ in range(12))
    head = R.choice(['Payment successful', 'Paid successfully', '✓ Money sent', 'Transaction Successful'])
    return page(f'''<div style="padding:40px 24px;text-align:center"><div style="font-size:64px;color:#1e8e3e">✓</div>
      <div style="font-size:22px;margin:8px">{head}</div><div style="font-size:44px;font-weight:bold">₹{a}</div>
      <div style="font-size:18px;margin-top:10px">Paid to {n}</div><div style="color:#555;margin-top:4px">{n.lower().replace(' ','.')}@ok{R.choice(['axis','hdfcbank','sbi','icici'])}</div>
      <div style="margin-top:30px;text-align:left;font-size:15px;line-height:2">UPI transaction ID<br><b>{upi}</b><br>{date()}, {R.randint(1,12)}:{R.randint(10,59)} {R.choice(['am','pm'])}<br>
      From: {R.choice(['HDFC Bank','State Bank of India','ICICI Bank'])} ••{R.randint(1000,9999)}<br>Powered by {app}</div></div>''')
def otp(i):
    code = R.randint(100000, 999999); svc = R.choice(['HDFC Bank', 'Amazon', 'Swiggy', 'IRCTC', 'Flipkart', 'Paytm', 'Google'])
    msg = R.choice([f'{code} is your OTP for login to {svc}. Do not share it with anyone.',
                    f'Your {svc} verification code is {code}. It expires in 10 minutes.',
                    f'Use OTP {code} to verify your transaction of Rs.{amt()} on {svc}. Never share your OTP.'])
    others = ''.join(f'<div style="padding:14px;border-bottom:1px solid #ddd"><b>{R.choice(["AX-","VM-","JD-"])}{svc[:5].upper()}</b><br><span style="color:#555">{R.choice(["Your order is out for delivery","Recharge successful","Bill payment due"])}</span></div>' for _ in range(3))
    return page(f'''<div style="padding:12px 16px;font-size:20px;font-weight:bold">Messages</div>
      <div style="margin:12px;padding:16px;background:#e8f0fe;border-radius:16px;font-size:17px;line-height:1.5">{msg}</div>{others}''')
def ticket(i):
    k = i % 3
    if k == 0:
        return page(f'''<div style="padding:20px;font-size:16px;line-height:1.9"><div style="font-size:22px;font-weight:bold">IRCTC e-Ticket</div>
          PNR: {R.randint(1000000000,9999999999)}<br>Train No./Name: {R.randint(10000,22999)} / {R.choice(['RAJDHANI EXP','SHATABDI EXP','DURONTO EXP'])}<br>
          Date of Journey: {date()}<br>From: {R.choice(['NDLS','MMCT','SBC','MAS'])} To: {R.choice(['HWH','PUNE','BCT','JP'])}<br>
          Coach: B{R.randint(1,9)} Berth: {R.randint(1,72)}<br>Booking Status: CNF<br>Total Fare: ₹{amt()}</div>''')
    if k == 1:
        return page(f'''<div style="padding:20px;font-size:16px;line-height:1.9;background:#fff8e1"><div style="font-size:24px;font-weight:bold">BOARDING PASS</div>
          {R.choice(['IndiGo','Air India','Vistara','Akasa Air'])}<br>Flight {R.choice(['6E','AI','UK','QP'])} {R.randint(100,999)}<br>
          From DEL To BOM<br>Date {date()}<br>Gate {R.randint(1,40)} &nbsp; Seat {R.randint(1,32)}{R.choice('ABCDEF')}<br>Boarding {R.randint(5,22)}:{R.randint(10,59)}<br>Sequence {R.randint(1,180)}</div>''')
    return page(f'''<div style="padding:20px;font-size:16px;line-height:1.9"><div style="font-size:22px;font-weight:bold">Booking Confirmed</div>
      {R.choice(['Jawan','Leo','Kalki 2898 AD','Pathaan'])} (UA)<br>{R.choice(['PVR','INOX','Cinepolis'])} : {R.choice(['Phoenix Mall','Forum','City Centre'])}<br>
      {date()} | {R.randint(1,11)}:{R.randint(10,59)} PM<br>Screen {R.randint(1,8)} Seats: {R.choice('ABCDEFGH')}{R.randint(1,20)}, {R.choice('ABCDEFGH')}{R.randint(1,20)}<br>Booking ID: {R.choice('ABCDEFG')}{R.randint(100000,999999)}<br>Ticket(s): 2</div>''')
def chat(i):
    lines = ['Hi, are you coming today?', 'Yes, will reach by 7', 'ok see you', 'Did you send the photos?', 'Sending now', 'Thanks!',
             'Call me when free', 'What time is the meeting tomorrow?', '10 am I think', 'Happy birthday!! 🎉', 'Thank you so much', 'lol', 'Reached home']
    msgs = ''.join(f'<div style="max-width:70%;margin:6px 10px;padding:8px 12px;border-radius:10px;font-size:16px;{"margin-left:auto;background:#dcf8c6" if R.random()<0.5 else "background:#fff"}">{R.choice(lines)}<span style="font-size:11px;color:#888"> {R.randint(1,12)}:{R.randint(10,59)} pm</span></div>' for _ in range(R.randint(8, 14)))
    return page(f'''<div style="background:#075e54;color:#fff;padding:14px;font-size:19px">← {R.choice(names)}<div style="font-size:12px">online</div></div>{msgs}
      <div style="position:fixed;bottom:10px;left:10px;right:10px;background:#fff;border-radius:20px;padding:10px;color:#888">Type a message</div>''', bg='#ece5dd')
def other(i):
    k = i % 2
    if k == 0:
        rows = ''.join(f'<div style="padding:14px 18px;border-bottom:1px solid #eee;font-size:17px">{t}<div style="color:#666;font-size:14px">{s}</div></div>' for t, s in
                       [('Network & internet', 'Mobile, Wi-Fi, hotspot'), ('Connected devices', 'Bluetooth, pairing'), ('Apps', 'Recent apps, default apps'),
                        ('Notifications', 'Notification history'), ('Battery', '87%'), ('Storage', '61% used - 78 GB free'), ('Sound & vibration', 'Volume, haptics'), ('Display', 'Dark theme, font size')])
        return page('<div style="padding:16px;font-size:26px">Settings</div>' + rows)
    para = ' '.join(R.choice(['The monsoon session of parliament began on Monday.', 'Markets closed higher as banking stocks rallied.',
                              'Scientists say the new telescope images reveal distant galaxies.', 'The city will add 40 new electric buses this year.',
                              'Experts recommend drinking enough water during summer.', 'The match was delayed due to rain in the evening.']) for _ in range(14))
    return page(f'<div style="padding:18px;font-size:24px;font-weight:bold">{R.choice(["City news","Tech today","Weekend reads"])}</div><div style="padding:0 18px;font-size:17px;line-height:1.6">{para}</div>')
async def main():
    labels = json.load(open(OUT + '/labels.json'))
    os.makedirs(os.path.join(OUT, AREA), exist_ok=True)
    async with async_playwright() as p:
        b = await p.chromium.launch(); pg = await b.new_page(viewport={'width': 360, 'height': 800}, device_scale_factor=3)
        for kind, fn, n in [('payment', payment, 24), ('otp', otp, 18), ('ticket', ticket, 18), ('chat', chat, 18), ('screenshot', other, 18)]:
            for i in range(n):
                await pg.set_content(fn(i))
                f = os.path.join(OUT, AREA, f'Screenshot_{kind}_{i:02d}.png')
                await pg.screenshot(path=f)
                labels[os.path.relpath(f, OUT)] = {'label': kind, 'source': 'synthetic screenshot'}
        await b.close()
    json.dump(labels, open(OUT + '/labels.json', 'w'), indent=0)
    print(len(labels))
asyncio.run(main())
