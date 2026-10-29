# Mi Volume Enhanced

为 HyperOS 音量面板加入降噪控制与分应用音量入口的 LSPosed 模块。

[English](README.md)

## 功能

### 多应用音量调节

有应用正在播放声音时，音量条上方会增加一个按钮，点击即可打开小米音质音效的分应用音量面板，单独调节每个应用的音量。

- 开启入口后，音质音效自带的悬浮球不再显示。

### 降噪控制

连接到兼容的耳机时，静音和勿扰按钮下方会增加一个按钮。

- 点击按钮切换聆听模式：默认在降噪、通透与关闭之间循环，也可以只在降噪与通透之间切换。
- 展开音量面板后，这一行还带有断开耳机连接的按钮。
- 可以在切换到降噪或通透时显示超级岛提示，默认关闭。

### 音量面板过长时

手机横屏时，加上降噪控制行的面板可能超出屏幕高度。可选择：

- **置于面板侧边**：降噪控制改为面板左侧的圆形按钮，长按可断开耳机连接；侧边空间不足时隐藏。
- **隐藏**：纵向空间不足时直接隐藏降噪控制。

## 支持的耳机

| 品牌 | 需要 |
| --- | --- |
| 小米、Redmi | 无 |
| Apple AirPods | 无 |
| Sony | [SonyPods](https://github.com/Mercury000/SonyPods) |
| 华为 | [HuaweiPods](https://github.com/Nshpiter/HuaweiPods) |
| OPPO | [OppoPods](https://github.com/Leaf-lsgtky/OppoPods) 或其 [1812z 分支](https://github.com/1812z/OppoPods) |

模块只使用系统的耳机服务和这些模块提供的广播接口。不支持通透的耳机只会在降噪开启与关闭之间切换。

## 安装

1. 从 [Releases](../../releases) 页面下载 APK 并安装。
2. 在 LSPosed 中启用模块，并勾选以下作用域：系统界面（`com.android.systemui`）、蓝牙扩展（`com.xiaomi.bluetooth`）和音质音效（`com.miui.misound`）。音质音效只在使用分应用音量入口时需要。
3. 重启这些作用域。可以在应用里用 Root 重启。应用里会显示每个作用域是否已加载模块。

提供分应用音量入口的模块请只保留一个。装有 AppVolumeBarHook 时会保留它的按钮，本模块的入口不再显示；Soundman 也提供类似的面板。

## 兼容性

面向小米 HyperOS 4 与 LSPosed（libxposed API 102）开发。如果系统更新后出现问题，请提交 issue，并附上出问题的作用域的报告（在应用中打开状态标签，再点击对应作用域的卡片即可复制），以及 LSPosed 日志中带 `MiVolumeEnhanced` 的行。

## 致谢

本项目由 zhhhyyyyyy 的 [HyperVolumeANC](https://github.com/zhhhyyyyyy/HyperVolumeANC) Fork 并重写而来。感谢 OppoPods（Leaf-lsgtky、1812z）、HuaweiPods（Nshpiter）和 SonyPods（Mercury000）的作者，本模块通过它们的接口工作；也感谢 [LibrePods](https://github.com/kavishdevar/librepods)，模块中的聆听模式图标参照了它的图标绘制。详见 [NOTICE](NOTICE)。

## 许可

[Apache License 2.0](LICENSE)

本项目与小米、Apple、Sony、华为和 OPPO 均无关联。
