# Clean-up screens: a simpler plan

Branch `claude/simple-plan-ui-redesign`. The goal is that anyone can see at a glance what will be removed, and see the
similar copies of a photo, without the rules, tags or risk tiers getting in the way. The reasoning is one tap away.

## The flow

1. **Clean up** (`CleanupScreen`, the new home of a plan). It shows how much space you can free, with one bar
   (orange is removed, purple is made smaller). Below that are plain piles, each with a 2×2 strip of its pictures, a
   count and a size. Apps you don't use have their own pile. One button, "Clean up 6.4 GB", goes to the last step. The
   old by-type view is still under *More › See everything by file type*.
2. **A pile** (`PileScreen`), for example *Memes and forwards*. A grid of pictures, each with a word on it: ✕ Remove,
   ✓ Keep, ★ Best · kept or ↘ Smaller. Tap a picture to switch it between removed and kept; the ⓘ button or a long
   press opens it. Copies of one photo sit together under one heading, with the copy that stays first, and a
   *Compare* link. Other files are rows. *Keep all* and *Done* are at the bottom.
3. **One file** (`ItemScreen`). The picture, *Will be removed / Will be made smaller / Stays*, one sentence saying
   why, Keep / Smaller / Remove buttons, and the similar photos right underneath with *Keep only the best one*.
4. **Why this decision?** (`WhyScreen`). This is the only place for the reasoning:
   - in short;
   - your choices and what each one frees;
   - what the app found;
   - the matching rules and raw details, folded away under *Rules and details (advanced)*.
5. **Confirm** (`ConfirmScreen`). It lists what happens in three lines (files, apps, made smaller) and says that files
   go to the Recycle bin for 30 days. One button. While the Recycle bin step is turned off (see `TrashAction`), the
   button reads *Finish my plan*, saves the plan, and says that nothing was removed. Making files smaller is listed as
   not available yet.

## Rules for the words and colours

- `core/Piles` puts each file in one pile by what was **suggested**, so a file you keep stays where you saw it, marked
  as kept. It also gives each pile a title and hint, and each file a one-sentence reason (`Piles.why`) with no rule
  names.
- Orange means removed, blue means kept and purple means made smaller (`Ui.REMOVE`, `Ui.KEEP`, `Ui.SMALLER`, with
  lighter versions for dark mode). Every badge also carries a word and a symbol, so the meaning never depends on colour
  alone.
- Touch targets are at least 44 dp, every picture and row has a spoken description saying what a double tap does, and
  text sizes follow the phone's font size.
- Changing anything on a finished plan opens it again for changes, with a short notice. Nothing has been deleted at
  that point, so there is no need to lock the plan.

## Tests

`jvmtest/.../PilesTest.java` checks:
- which pile each file goes into;
- that a kept file stays in its pile;
- that counts and sizes follow your choices;
- that reasons are plain sentences.

It runs in CI alongside `FinalPlanTest`.
