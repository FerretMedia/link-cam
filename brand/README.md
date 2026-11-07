# Brand

Link Cam's name, glyph and launcher icon. These are **not** under the Apache
License; see [LICENSE](LICENSE) and [TRADEMARKS.md](../TRADEMARKS.md).

- `glyph.svg`: the glyph, a little monitor with a tally light, drawn on a
  24-unit grid with a 2-unit stroke so it sits beside Linker's mark. The dot is
  `#F52BD0` (crimson, brightened), the colour Link Cam keeps for REC and LIVE.
- `android/res/`: the same glyph as Android resources, plus the adaptive
  launcher icon and the app name. The app module adds this folder as a resource
  directory, so a fork can swap the whole folder for its own.
- `ios/`: the same for the iPad app. `Brand.xcassets` holds the app icon and
  the glyph, `Brand.xcconfig` the app name, and `icon.svg` is the icon's
  source (render it to `AppIcon.png` at 1024 px, without alpha).
