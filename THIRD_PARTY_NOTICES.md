# Third-party notices

AF File Manager includes open-source components in addition to the dependencies resolved by Gradle.

## ConnectBot Terminal

`org.connectbot:termlib:0.1.0` provides the Jetpack Compose terminal renderer and terminal emulation.

- Project: https://github.com/connectbot/termlib
- Copyright 2025 Kenny Root and contributors
- License: Apache License 2.0 — https://www.apache.org/licenses/LICENSE-2.0

## libvterm

ConnectBot Terminal embeds libvterm for terminal emulation.

- Project: https://www.leonerd.org.uk/code/libvterm/
- Copyright Paul Evans and contributors
- License: MIT

## Shizuku API

`dev.rikka.shizuku:api:13.1.5` and `dev.rikka.shizuku:provider:13.1.5` provide the optional local Shizuku permission and user-service connection.

- Project: https://github.com/RikkaApps/Shizuku-API
- Copyright RikkaApps contributors
- License: MIT — https://github.com/RikkaApps/Shizuku-API/blob/master/LICENSE

## libsu

`com.github.topjohnwu.libsu:core:6.0.0`, `service:6.0.0`, and `nio:6.0.0` provide the optional root service and privileged file-system bridge.

- Project: https://github.com/topjohnwu/libsu
- Copyright John Wu and contributors
- License: Apache License 2.0 — https://github.com/topjohnwu/libsu/blob/master/LICENSE

## Offline interface translation assets

The generated language packs were prepared during development with Meta's NLLB-200 distilled 600M model. The model and translation runtime are not included in the Android app. Standard Android action labels are taken from the Android Open Source Project translations where an exact match exists.

- NLLB-200 model: https://huggingface.co/facebook/nllb-200-distilled-600M
- NLLB-200 license: Creative Commons Attribution-NonCommercial 4.0
- Android Open Source Project: https://source.android.com/
- AOSP license: Apache License 2.0

The notices above do not change AF File Manager's [PolyForm Noncommercial License 1.0.0](LICENSE). Each third-party component remains governed by its own license.
