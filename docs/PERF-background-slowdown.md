# Background slowdown: measurements (Samsung S23 FE, Try on my pictures, 100 newest pictures, 24 Nov 2026)

Same 100 pictures every run (newest, total-count field), results ignored from earlier runs, nothing else running
(YouTube stopped), measured by the app's own stage timer (Perf.Timer), driven through Phone Pilot.
The resulting plan was identical in the runs compared (frees 126.4 MB, 19 delete, 69 compress, 12 keep).

| Run | Speed-ups | App | Total | Out of sight |
|---|---|---|---|---|
| 1 | OFF | foreground | 264 s | 0% |
| 2 | OFF | background | 1,868 s (31 min) | 100% |
| 3 | ON | foreground, but 49% of it in background (user opened the chat) | 303 s | 49% |
| 4 | ON | background | 1,127 s (19 min) | 100% |

Per stage (seconds): 

| Stage | 1 fg OFF | 2 bg OFF | 4 bg ON |
|---|---|---|---|
| Analysing photos | 26.4 | 301.8 | 152.9 |
| Comparing similar photos | 10.2 | 185.4 | 62.6 |
| Sorting pictures | 10.8 | 181.4 | 59.7 |
| Reading text in pictures | 92.2 | 736.4 | 383.0 |
| Looking at what photos show | 4.2 | 22.3 | 23.9 |
| Mirage | 98.4 | 415.3 | 402.6 |

Finding: every stage is slower in the background, including plain-Java ones, so the cause is the OS giving a backgrounded
app far less CPU (Samsung power management), not only the WebView. Speed-ups (renderer priority, thread priority, 4 pictures
to the text reader at once) cut the background run by 40%, but it is still ~4x slower than the foreground.
Run 3 is not a clean foreground run; a clean foreground run with speed-ups ON is still to do.
