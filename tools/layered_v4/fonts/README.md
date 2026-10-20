# Review caption font

`ReviewLabels.otf` is a renamed, glyph-subset derivative of Noto Sans CJK SC Regular
(from the NotoSansCJK-Regular.ttc SC face). It includes only characters used by
SAM review captions and ASCII. This avoids network/font-package installation in
Colab and renders real Chinese labels instead of missing-glyph squares.

Upstream: https://github.com/notofonts/noto-cjk
License and copyright: [LICENSE.NotoCJK](LICENSE.NotoCJK), SIL Open Font License 1.1.
The derived family is renamed **FloatDeck Review Labels**.
