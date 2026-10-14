# Spot-check list

The finding that the label read should be the primary house number rests on 20 crop readings
made by an agent (see `score.js`). That is a second opinion. These are the cards worth a
person checking by eye before the finding is relied on.

## The four that matter most

These are the ones where a mistake would change the conclusion, so they are listed first. The
first three are the label read winning; the fourth is the vote winning.

    serial 8     crop says 90-5-5/5      label "90-5-5/5"   vote "1-1-1"
    serial 21    crop says 90-23-23/5      label "90-23-23/5"   vote "1-1-5"
    serial 159   crop says 90-31-31/4   label "11-2-26/8B"  vote "90-31-31/4"

    serial 458   the crop spans TWO printed lines:
                "SATYANARAYANA Kapa"
                ": 90-46-6/1"
                Neither reader produced the value. The true value is 90-46-6/1.
                This one is a separate defect, not evidence about either reader.

## The rest, for completeness

    26, 31, 36, 59, 86, 101, 109, 271, 342, 388, 428, 497, 602, 623, 631, 638

## How to look at one

    node tools/ocr/sheet.js <roll.pdf> <run.jsonl> /tmp/check.png 8,21,159,458

Each tile shows the serial, the candidates each reader produced, the crop itself at four
times size, and what the pipeline currently prints. The crop is the evidence; the text above
it is a claim about the crop.

## What a spot-check can and cannot settle

It can confirm or overturn "the label read wins where they disagree", which is the decision
the change rests on.

It cannot give a whole-roll accuracy figure. 20 cards out of 1129 is not a sample that
supports one, and it is a deliberately biased sample -- only the cards where the two readers
differ. The honest statement of what is known is:

  - the two readers agree on 997 of 1129 cards, so the choice between them cannot affect
    those 997 whatever a spot-check says
  - on 20 sampled disagreements the label read was right 16 times and the vote 3, with 1
    unresolvable by either
  - the roll-wide accuracy of either reader is therefore still unmeasured, and needs a random
    sample scored by a person before any number is quoted

That last point is the one not to skip. A contact sheet of 24 random cards, scored by
`sheet.js ... random:24`, would give it.