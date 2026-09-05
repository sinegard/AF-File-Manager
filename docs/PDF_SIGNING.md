# PDF signing behavior

AF File Manager's PDF signing tool adds a **visible handwritten mark**. It does not create a
qualified or certificate-backed cryptographic electronic signature.

## Interaction matrix

| Control or gesture | Result | Boundary and cancellation behavior |
| --- | --- | --- |
| Drag in the white signature pad | Adds one sampled stroke | Limited to 64 strokes and 4,096 points; input stays in memory until applied or cancelled |
| Undo | Removes the most recent stroke | Does nothing when the drawing is empty |
| Reset | Removes every stroke so the signature can be redrawn | Does not touch the PDF |
| Next | Opens page placement | Disabled until at least one stroke exists |
| Page arrows or page number | Selects the destination page | Only pages that exist can be selected |
| Drag on the outlined signature | Moves the mark | Starts only inside the mark and remains inside the page |
| Drag the bottom-right corner handle | Resizes the mark | Preserves its aspect ratio, anchors the opposite corner, and remains inside the page |
| Add signature | Writes to the private working copy | One bounded operation; repeat taps are blocked while it runs |
| Back or close before applying | Cancels signing | Original and working PDF content remain unchanged |
| Save | Publishes through the existing origin conflict check | Enabled only after a complete, verified working copy exists |
| Save as | Writes to a chosen phone or connected-server location | Existing destinations require an explicit conflict choice |

Password-protected PDFs and PDFs that already contain a cryptographic signature are not modified.
This avoids silently invalidating an existing signature. A failed transformation leaves the previous
working copy intact, and closing the preview removes private temporary files unless the user saved a
copy intentionally.
