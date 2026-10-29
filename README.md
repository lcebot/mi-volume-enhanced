# Mi Volume Enhanced

An LSPosed module that adds noise control and a per-app volume entry to the HyperOS volume panel.

[中文说明](README_zh.md)

## Features

### Per-app volume

While an app is playing audio, a button appears above the volume bar. Tapping it opens MiSound's per-app volume panel, where each app's volume is set on its own.

- MiSound's floating ball is not shown while the entry is enabled.

### Noise control

With a compatible headset connected, a button appears below the silent and do-not-disturb buttons.

- Tapping it switches the listening mode: through noise cancelling, transparency and off by default, or between noise cancelling and transparency only.
- In the expanded volume panel the row also has a button that disconnects the headset.
- Switching to noise cancelling or transparency can show a HyperOS island; off by default.

### When the volume panel is too tall

On a phone in landscape, the panel with the noise control row can exceed the screen height. Choose:

- **Beside the panel**: noise control becomes a round button left of the panel, and a long press disconnects the headset; it is hidden when there is no room beside the panel.
- **Hidden**: noise control is hidden when there is not enough vertical space.

## Supported headsets

| Brand | Requirement |
| --- | --- |
| Xiaomi, Redmi | none |
| Apple AirPods | none |
| Sony | [SonyPods](https://github.com/Mercury000/SonyPods) |
| Huawei | [HuaweiPods](https://github.com/Nshpiter/HuaweiPods) |
| OPPO | [OppoPods](https://github.com/Leaf-lsgtky/OppoPods) or its [1812z fork](https://github.com/1812z/OppoPods) |

The module only uses the system's headset service and the broadcast interfaces of these modules. Headsets without transparency only switch noise cancelling on and off.

## Installation

1. Download the APK from the [Releases](../../releases) page and install it.
2. In LSPosed, enable the module with these scopes: System UI (`com.android.systemui`), the Bluetooth extension (`com.xiaomi.bluetooth`) and MiSound (`com.miui.misound`). MiSound is only needed for the per-app volume entry.
3. Restart these scopes. The app can restart them with root, and shows whether each scope has loaded the module.

Please keep only one module that adds a per-app volume entry. With AppVolumeBarHook installed, its button is kept and this module's entry stays hidden; Soundman provides a similar panel.

## Compatibility

Built for Xiaomi HyperOS 4 with LSPosed (libxposed API 102). If a system update breaks something, open an issue and attach the report of the affected scope (open the status chip in the app, then tap that scope's card to copy it), together with the `MiVolumeEnhanced` lines of the LSPosed log.

## Credits

This project is a rewrite of [HyperVolumeANC](https://github.com/zhhhyyyyyy/HyperVolumeANC) by zhhhyyyyyy, which it is forked from. Thanks to the authors of OppoPods (Leaf-lsgtky, 1812z), HuaweiPods (Nshpiter) and SonyPods (Mercury000), whose interfaces it talks to, and to [LibrePods](https://github.com/kavishdevar/librepods) for the listening mode icons it draws after. See [NOTICE](NOTICE).

## License

[Apache License 2.0](LICENSE)

This project is not affiliated with Xiaomi, Apple, Sony, Huawei or OPPO.
