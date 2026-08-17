# Branding / Logos

Official distribution logos used to identify distributions in the application UI.
All assets are official brand resources from the respective distributions' websites,
used here solely to display distribution identity inside the application.

## Logos

| File | Distribution | Source URL | Notes |
| --- | --- | --- | --- |
| `debian-logo.svg` | Debian | https://github.com/gilbarbara/logos | Debian spiral logo |
| `archlinux-logo.svg` | Arch Linux | https://github.com/gilbarbara/logos | Arch logo |
| `ubuntu-logo.svg` | Ubuntu | https://github.com/gilbarbara/logos | Ubuntu logo |
| `alpine-logo.svg` | Alpine Linux | https://alpinelinux.org/alpinelinux-logo.svg | Official logo from the Alpine Linux website (SVG) |
| `kali-logo.svg` | Kali Linux | https://www.kali.org/images/kali-logo.svg | Official Kali dragon logo from kali.org (SVG) |
| `slackware-logo.png` | Slackware | https://www.slackware.com/grfx/shared/slackware_traditional_website_logo.png | Official traditional website logo from slackware.com (PNG; no official SVG available) |

## License

Official brand resources of the respective distributions. Debian, Arch, Ubuntu and
Alpine logos are trademarks of their respective projects; Kali and Slackware logos are
trademarks of their respective organizations. Used for identification purposes only.

## Fonts（字体）

**MiSans**（小米开源字体，免费商用）：**决策：暂不打包完整字体**。
- 来源已确认：mobeicanyue/misans-webfont releases（v4.003.1 fonts.tar.zst，GitHub）
- 完整字体集体积过大（Global 版覆盖 600+ 语言，数百 MB）；npm misans 包为
  网页 woff2 子集（43MB 但单文件仅 ~0.2MB，缺字，不适合 Android TTF 集成）
- 权衡：k30p（小米）系统字体即 MiSans，打包收益为零；其他设备收益边际，
  但 APK 体积 +10~20MB 成本显著
- 后续可选：设置页"可选下载 MiSans"（下载完整 TTF 到 filesDir 后动态加载），
  正式发布前由用户拍板
