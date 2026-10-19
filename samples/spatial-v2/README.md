# Schema fixture only

`SYNTHETIC_SCHEMA_ONLY.spatial-v2.zip` is a 96×160 toy CPU test fixture, NOT the user's fairy wallpaper, NOT GPU output, NOT reviewed layered art. It deliberately declares `UNREVIEWED-DRAFT`. Use `python -m tools.layered_v4.validate ... --allow-draft` only for structural checks; normal validation rejects it. Never import it into the Android V1 player.

For the actual 1648×2944 wallpaper, run the Colab Notebook with the uploaded V1 ZIP, review masks, execute GPU inpainting and inspect the generated image previews. No real-photo model output is claimed from CPU-only tests.
