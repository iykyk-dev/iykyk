# IYKYK branding

`logo.svg` is the master logo: the shield traced to vector paths from
`logo-original.png` (colours #1B3E5E navy and #ED8532 orange). Everything else is
generated from it:

| File | Use |
| --- | --- |
| `logo.svg`, `logo-mono.svg` | master, one-colour version |
| `android/app/src/main/res/drawable/ic_launcher_foreground.xml` | Android adaptive icon (vector, sharp at any size) |
| `android/app/src/main/res/drawable/ic_launcher_monochrome.xml` | Android 13+ themed icon |
| `android/desktop/icon.ico`, `android/desktop/src/main/resources/icon.png` | Windows (16–256 px), tray |
| `site/img/logo.svg`, `logo.png`, `apple-touch-icon.png` | site, favicon |
| `playstore-icon-512.png`, `appstore-icon-1024.png` | store listings |

To change the logo, edit `logo.svg` and regenerate the files above from it
(the paths can be pasted into the Android vector drawables as they are).
