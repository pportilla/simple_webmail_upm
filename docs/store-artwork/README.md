# Google Play artwork

The SVG files here reuse the app's existing envelope launcher vector in
`app/src/main/res/drawable/ic_launcher_foreground.xml`. They contain no official UPM crest.
English and Spanish artwork identifies the app as unofficial and credits zango.

To regenerate the SVG sources and PNG exports, install Python 3, CairoSVG and the
DejaVu Sans fonts, then run from the repository root:

```bash
python3 scripts/generate-store-artwork.py
```

The generator writes the 512 × 512 icon and 1024 × 500 feature graphic to each locale's
`fastlane/metadata/android/<locale>/images/` directory. Review the exported graphics after
changing fonts or the source icon. Those files are store artwork, not app screenshots.

Phone screenshots are unmodified captures of the running Android app. Use
`scripts/capture-store-screenshots.py` with a disposable emulator, follow its safety checks,
and inspect each image. The neighboring `CAPTURE.json` files record capture provenance and
SHA-256 checksums. The fixture uses reserved `.invalid` addresses and contains no real mail
account or password.
