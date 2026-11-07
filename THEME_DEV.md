# Theme Development Guide

**English** · [简体中文 ↓](#简体中文) · [繁體中文 ↓](#繁體中文)

[← Back to README](README.md) · [Plugin development guide](PLUGIN_DEV.md)

> Bundled themes: `data/themes/night`, `data/themes/sakura`, `data/themes/contrast`
> (created on first launch — edit them freely, they are not overwritten).
> Copy-ready sample: [`examples/my-theme/`](examples/my-theme/)
>
> Three moving parts: `theme.css` (+ optional extra CSS), `theme.json` (metadata & options),
> optional `background.*` / `preview.png`.

---

## English

### 1. What a theme can change

A theme is **CSS overlaid on top of the launcher's default stylesheet**, plus a few declarative
options handled by the launcher itself. You can change:

| | Examples |
|---|---|
| Colours | window background, top bar, cards, buttons, text, borders, chips |
| Decorations | radii, border widths, shadows (`-fx-effect`), gradients |
| Typography | font size, weight, family (installed fonts only) |
| Background | one image, how it is fitted, how much it is dimmed |
| Metadata | name (multi-language), author, version, description, thumbnail |

You cannot change layout structure, add new widgets, load remote assets, or ship fonts (`@font-face`
is rejected). Themes apply to the main window **and** every dialog, because dialogs use the same
stylesheet chain.

> **Design tokens are the whole trick.** Every colour in the launcher — including the JavaFX native
> controls (ComboBox, Spinner, CheckBox, ScrollBar, TabPane, ContextMenu) — resolves through 40
> `-brisk-*` tokens defined in one place. A theme normally just overrides those tokens in `.root`;
> you do **not** have to hunt down selectors. Full list: [Appendix A](#15-appendix-a-design-tokens).
>
> **The startup animation is themeable too.** The small window shown right after double-click uses the
> same tokens (so it already follows your theme), and has its own `.splash-*` classes. If you want a
> different logo treatment, override `.splash-root` (the full-bleed background), `.splash-card`, `.splash-ring`,
> `.splash-bolt`, `.splash-title`, `.splash-status`, `.splash-track`, `.splash-bar`.
>
> The picture the JVM paints *before* JavaFX is up (`splash.gif` next to the launcher) is generated
> from the same tokens, so it matches as well: it is rewritten the first time the launcher runs with a
> theme and again whenever you switch themes. The copy shipped in the build is the default-theme one.

### 2. Quick start

1. Copy `examples/my-theme/` into `data/themes/` (folder name becomes the theme id).
2. Open **Settings → Appearance → Theme**, pick it — the switch is instant, no restart.
3. Edit `theme.css`, press **Rescan themes**（重新扫描主题）or pick another theme and back.

Built-in themes are the best starting point: `data/themes/night/theme.css` shows a dark colour
override, `sakura` a light one, `contrast` thick borders and high-contrast text.

### 3. Folder format

```
data/themes/my-theme/
  theme.css            required (main stylesheet)
  extra.css            optional (any number of *.css)
  theme.json           optional: metadata + options (recommended)
  pack.properties      optional legacy: name / author / version
  background.png       optional background image (png / jpg / jpeg / webp)
  logo.png             optional brand mark (top bar + window/taskbar icon)
  preview.png          optional thumbnail shown in Settings
```

The folder name is the theme id: `[A-Za-z0-9._-]` works everywhere; anything else is used as-is but
avoid spaces. `default` is reserved for the built-in theme.

### 4. `theme.json` reference

```json
{
  "name": {
    "zh_CN": "示例：午夜紫",
    "zh_TW": "範例：午夜紫",
    "en_US": "Sample: Midnight Purple"
  },
  "description": { "zh_CN": "一行说明", "en_US": "One line" },
  "author": "you",
  "version": "1.0.0",
  "homepage": "https://example.com",
  "base": "dark",
  "accent": "#B79CFF",
  "styles": ["theme.css", "extra.css"],
  "background": "background.png",
  "backgroundFit": "cover",
  "backgroundDim": 0.35,
  "logo": "logo.png",
  "preview": "preview.png"
}
```

| Field | Type | Default | Meaning |
|---|---|---|---|
| `name` | string **or** `{locale: string}` | folder name | Shown in the theme dropdown. A locale map is looked up with the current UI language, then `zh_CN`, then `en_US`, then the first entry. |
| `description` | string or map | `""` | Shown under the dropdown (Settings → Appearance). |
| `author` / `version` | string | `""` | Appended to the dropdown label and shown as metadata. |
| `homepage` | string | `""` | Informational only. |
| `base` | `light` / `dark` | `light` | A hint: adds 🌙/☀️ in Settings, exposed to plugins via `context.theme().dark()`, and gives a default `backgroundDim` of 0.25 when you don't set one. |
| `accent` | `#RRGGBB` | `""` | Your theme's signature colour; exposed to plugins so their panels can match. |
| `styles` | array of file names | all `*.css` | Applied in this exact order. Without it: `theme.css` first, then the rest alphabetically. Paths must stay inside the theme folder. |
| `background` | file name | auto-detect `background.*` | Background image. |
| `backgroundFit` | `cover` / `contain` / `repeat` / `center` | `cover` | How the image fills the window. |
| `backgroundDim` | number 0–1 | `base == dark ? 0.25 : 0` | Darkens the image so foreground text stays readable. |
| `logo` | file name | auto-detect `logo.*` | **Brand mark** shown at the left of the top bar (≈30 px) and used as the window / taskbar icon, so it follows the theme. Square PNG, 128–256 px recommended; transparent background; must stay readable when scaled down to ~26 px (avoid thin lines and text). No logo → the launcher falls back to its built-in sakura-pink cube. |
| `preview` | file name | auto-detect `preview.*` | Thumbnail in Settings (scaled to 132×74). |
| `wordmark` | boolean | `true` | Show the big **Brisk** wordmark next to the logo. The small "Brisk Launcher" label beside it is always shown — this only hides the large text. |

`pack.properties` (legacy) is still read for `name=` / `author=` / `version=` when `theme.json` is
absent — it cannot express `styles` or background options.

### 5. How your CSS is applied

1. `styles/mcl-theme.css` (bundled default, which defines all the `-brisk-*` tokens),
2. your `styles` in order (last wins on equal specificity).

**The recommended theme is a token override** — 10 lines and the whole launcher, including dialogs and
native controls, follows:

```css
.root {
    -brisk-bg: #14101F;          /* window / page background */
    -brisk-surface: #1E1830;     /* cards, inputs, lists, buttons */
    -brisk-border: #332A4D;
    -brisk-text: #EDE8FA;
    -brisk-accent: #B79CFF;      /* accent text / focus ring */
    -brisk-on-accent: #14101F;   /* text on accent-filled buttons */
}
```

Override only what you care about — the rest keeps the default values. Everything else (selector
overrides) still works and is the escape hatch for radii, borders, spacing and one-off tweaks:

```css
.card      { -fx-border-width: 1; -fx-effect: none; }
.nav-button { -fx-font-weight: bold; }
```

### 6. Background image

* Formats: PNG / JPG / JPEG / WEBP, **max 16 MB**.
* Fixed to the window, content area stays on top and keeps its own background — so cards and lists
  remain readable (a fully transparent content background is not supported).
* The launcher rounds all four corners and optionally dims the image (`backgroundDim`).
* Big photos: 1920×1080 is plenty; a 4K PNG mostly costs startup time.

### 7. Thumbnail

`preview.png` (any of png/jpg/jpeg/webp) is scaled to 132×74 in Settings → Appearance.
It is also a cheap way to say "this theme is dark" at a glance. Keep it under a few hundred KB.

### 8. Safety rules (what gets rejected)

Themes are files downloaded from the internet, so the launcher refuses the risky ones. A CSS file is
**not loaded** (and the theme shows a warning in Settings) when it:

* references `http:` / `https:` / `file:` / `jar:` / protocol-relative `//` in `url(...)`
  (privacy beacons, local file probing);
* contains `@font-face`;
* is bigger than 2 MB;
* is named outside the theme folder (`"styles": ["../x.css"]`).

Your theme stays in the list with a warning instead of silently doing nothing — check
**Settings → Appearance** when a theme looks unstyled.

### 9. Performance

* The launcher caches the theme list, validated CSS and background per folder shortly after it first
  looks at `data/themes/`; re-scanning happens only when the folder or a file actually changes.
* Every dialog reuses the same stylesheet chain — there is no per-dialog CSS parsing of your files.
* Keep the CSS under a few thousand lines and avoid `-fx-effect` animations; JavaFX parses CSS once and
  layout cost is per-node, so effects on lists are the expensive part.
* Background images are decoded once per theme switch, not per page.

### 10. Multi-language metadata

Use the locale-map form for `name` / `description` (see §4). Keys are the same tags the launcher uses:
`zh_CN`, `zh_TW`, `zh_HK`, `en_US` (custom language pack ids work too, but keep at least `zh_CN` and
`en_US` so there is always something to fall back to).

### 11. Publishing

* Ship a folder (or a zip the user unzips into `data/themes/`) whose **folder name is your theme id**;
* keep `theme.json` + `preview.png`; bump `version` when you change colours so users can tell builds apart;
* never write outside your folder, and never ask users to edit `mcl-theme.css`;
* themes are per-user files: `data/themes/` is world-readable but not synced.

### 12. Plugins and themes

A plugin can read the active theme through `context.theme()`:

```java
PluginContext.ThemeInfo theme = context.theme();
theme.dark();        // true for dark themes
theme.accent();      // "#B79CFF" or "" if the theme declares none
theme.name();        // localized display name
```

That lets a plugin panel pick readable colours instead of hardcoding white-on-white.

### 13. Limits (on purpose)

* No layout changes, no new widgets, no per-window themes (one theme applies to everything).
* No remote images/fonts (`@font-face` is banned), no `file://` references.
* Themes cannot localise text — that is language packs (`lang/<id>/messages.properties`).
* `default` cannot be overridden; create your own id instead.

### 14. Troubleshooting

| Symptom | Cause / fix |
|---|---|
| Theme not in the dropdown | folder is not under `data/themes/`, has neither a `*.css` nor `theme.json`/`pack.properties`, or uses the reserved id `default` |
| Theme listed but nothing changes | CSS rejected by the safety rules → warning shown in Settings; also check `theme.json` syntax (a broken JSON only costs you the metadata, not the CSS) |
| Background image invisible | content area is opaque; only the window behind it shows the image. Check `background` file name / extension |
| Text unreadable on dark theme | set `-brisk-text*` tokens; also check `-brisk-on-accent` (text on accent-filled buttons) |
| Controls still mint after switching theme | the theme overrides selectors instead of the `-brisk-*` tokens — override tokens (Appendix A) |
| Button text invisible on a dark theme | `-brisk-on-accent` must contrast with `-brisk-accent`/`-brisk-accent-strong` |
| Warning: "CSS 被安全检查拒绝" | remove `url(http…)`, `file:`, `jar:` or `@font-face` |
| Warning: "没有可用的 CSS" | `styles` points at files that do not exist, or every CSS file was rejected |
| Changes do not show up | press **Rescan themes**; if you edited a file while the launcher was open, the folder timestamp may not have changed — reselect the theme |
| Theme affects dialogs too | by design; one stylesheet chain for the whole app |

### 15. Appendix A: design tokens

These 40 tokens are defined once in `mcl-theme.css` (`.root`) and used by **every** launcher widget.
Override the ones you care about inside your own `.root { … }` and the change propagates to the main
window, all dialogs and the native JavaFX controls. Names are stable within API major version 2.

| Token | Default | Typical use |
|---|---|---|
| `-brisk-bg` | `#F4FCFA` | window / page background |
| `-brisk-bg-soft` | `#EDFBF8` | subtle page tint, startup overlay |
| `-brisk-surface` | `#FFFFFF` | cards, inputs, lists, buttons |
| `-brisk-surface-2` | `#DFF4F0` | nested cards, entry cards |
| `-brisk-surface-3` | `#CFF2EC` | deeper insets |
| `-brisk-surface-hover` | `#DCF6F1` | hover fill |
| `-brisk-surface-selected` | `#E4F8F4` | selected rows |
| `-brisk-glass` | `rgba(255,255,255,0.55)` | translucent panels |
| `-brisk-glass-strong` | `rgba(255,255,255,0.7)` | denser glass |
| `-brisk-overlay` | `rgba(255,252,246,0.96)` | popups / menus |
| `-brisk-overlay-solid` | `#FFFCF7` | solid popup surface |
| `-brisk-scrim` | `rgba(42,24,12,0.62)` | dimming behind overlays |
| `-brisk-border` | `#BFE9E2` | standard border |
| `-brisk-border-soft` | `#CBE9E4` | separators / dividers |
| `-brisk-border-2` | `#A8D8D1` | scrollbar thumb, stronger borders |
| `-brisk-border-strong` | `#9FE3D8` | window border |
| `-brisk-text` | `#14453F` | headings, body text |
| `-brisk-text-body` | `#1C5A54` | body text on surfaces |
| `-brisk-text-dim` | `#3E7B74` | secondary text |
| `-brisk-text-muted` | `#5E8B85` | hints |
| `-brisk-text-faint` | `#7FA9A3` | placeholder text |
| `-brisk-text-tag` | `#2E6B64` | text on warm tags |
| `-brisk-accent` | `#0E9C8D` | accent text, focus ring, selection bar |
| `-brisk-accent-2` | `#0F7C72` | darker accent text |
| `-brisk-accent-strong` | `#12BFA8` | primary button gradient end |
| `-brisk-accent-soft` | `#5AD8C6` | primary button gradient start |
| `-brisk-accent-deep` | `#0A6B60` | pressed / hover accent |
| `-brisk-accent-border` | `#7FDCCB` | accent border |
| `-brisk-on-accent` | `#FFFFFF` | text/glyphs on accent fill |
| `-brisk-warn-text` | `#B0764A` | warning text |
| `-brisk-warn-bg` | `#FFF0DE` | warning / tag background |
| `-brisk-warn-glass` | `rgba(255,227,196,0.6)` | warning glass |
| `-brisk-danger` | `#C0392B` | errors |
| `-brisk-ok` | `#2E8B62` | success |
| `-brisk-warning` | `#C96A1B` | warnings |
| `-brisk-shadow` | `rgba(20,69,63,0.12)` | card shadow |
| `-brisk-shadow-strong` | `rgba(20,69,63,0.18)` | stronger shadow |
| `-brisk-accent-shadow` | `rgba(18,191,168,0.22)` | accent glow |
| `-brisk-topbar-from` | `#E8FAF6` | top bar gradient start |
| `-brisk-topbar-to` | `#D3F1EB` | top bar gradient end |

**Why the native controls follow.** The default stylesheet also maps JavaFX's Modena semantics onto
these tokens: `-fx-base`, `-fx-background`, `-fx-control-inner-background`(-alt), `-fx-accent`,
`-fx-focus-color`, `-fx-faint-focus-color`, `-fx-selection-bar`(-non-focused), `-fx-text-base-color`,
`-fx-text-background-color`, `-fx-outer-border`, `-fx-inner-border`, `-fx-body-color`,
`-fx-mark-color`, `-fx-highlight-fill`, `-fx-highlight-text-fill`, `-fx-default-button`.
That is why ComboBox, Spinner, CheckBox, RadioButton, ScrollBar, TabPane and ContextMenu change colour
without you writing a single selector for them.

**Plugins can use the same tokens** in their own inline styles
(`label.setStyle("-fx-text-fill: -brisk-text-muted;")`), so a plugin panel matches whatever theme is
active. See the [plugin guide](PLUGIN_DEV.md).

**Guard.** The repository ships `.work/tools/theme_guard.py`: it fails if the base stylesheet contains
a colour literal outside the token block, if a token is used but not defined, or if Java code
re-introduces a hardcoded `-fx-…: #hex` style.

### 16. Appendix B: CSS selector inventory

These are the selectors the bundled stylesheet actually defines (149, alphabetically). Override any of
them; the launcher's own style classes (`mcl-backdrop`, `mcl-backdrop-dim`, `app-root`) are managed by
code — expect them to move around.

```

.account-switch-btn  |  .account-switch-btn:hover
.app-root  |  .button
.button:focused, .toggle-button:focused  |  .button:hover
.button:pressed, .pill-toggle:pressed, .toggle-button:pressed  |  .card
.cat-hint  |  .check-box
.check-box .box  |  .check-box .text
.check-box:selected .mark  |  .color-picker
.combo-box .list-cell  |  .combo-box, .choice-box
.combo-box-popup .list-view  |  .config-shell .check-box > .box
.config-shell .check-box:selected > .box  |  .config-shell .check-box:selected > .box > .mark
.config-shell .scroll-pane, .config-shell .scroll-pane .viewport  |  .config-shell .text-area
.config-shell .text-area, .config-shell .text-area .scroll-pane, .config-shell .text-area .scroll-pane .viewport, .config-shell .text-area .content  |  .config-shell .text-field
.config-shell .titled-pane > .content  |  .config-shell .titled-pane > .title
.config-shell .titled-pane > .title > .text  |  .context-menu
.device-code  |  .edge-to-edge .scroll-bar:vertical, .edge-to-edge .scroll-bar:horizontal
.edge-to-edge, .edge-to-edge > .viewport  |  .entry-card
.entry-card:hover  |  .entry-card:hover, .card:hover
.entry-id  |  .filter-chip
.filter-chip:selected  |  .icon-button
.icon-button:armed, .icon-button:pressed  |  .icon-button:hover
.icon-button:hover, .window-button:hover  |  .instance-meta
.instance-name  |  .link-toggle
.link-toggle:hover  |  .link-toggle:selected
.list-cell  |  .list-cell:filled:selected:focused
.list-cell:focused  |  .list-cell:hover
.list-cell:selected  |  .list-view
.list-view:focused  |  .logo
.logo-sub  |  .menu-button, .split-menu-button
.menu-button:hover, .split-menu-button:hover  |  .menu-item
.menu-item .label  |  .menu-item:focused
.nav-button  |  .nav-button:hover
.nav-button:selected  |  .page
.pill-toggle  |  .pill-toggle:focused, .side-button:focused, .nav-button:focused, .filter-chip:focused, .icon-button:focused
.pill-toggle:hover  |  .pill-toggle:selected
.preview-pane  |  .primary-button
.primary-button:disabled  |  .primary-button:hover
.progress-bar > .bar  |  .progress-bar > .track
.progress-bar.login-progress  |  .progress-bar.login-progress > .bar
.progress-bar.login-progress > .track  |  .radio-button
.radio-button .radio  |  .radio-button > .radio
.radio-button:selected .dot  |  .radio-button:selected > .radio > .dot
.root  |  .root-pane
.running-chip  |  .running-chip:hover
.scroll-bar  |  .scroll-bar .increment-button, .scroll-bar .decrement-button
.scroll-bar .thumb  |  .section-title
.side-button  |  .side-button:hover
.side-button:selected  |  .skin-preview-box
.skin-tile  |  .skin-tile:hover
.slider .thumb  |  .slider .track
.spinner  |  .spinner .increment-arrow, .spinner .decrement-arrow
.spinner .increment-arrow-button, .spinner .decrement-arrow-button  |  .spinner .text-field
.splash-bar  |  .splash-bolt
.splash-card  |  .splash-ring
.splash-root  |  .splash-status
.splash-title  |  .splash-track
.startup-card  |  .startup-instance
.startup-overlay  |  .startup-paw
.startup-progress .bar  |  .startup-progress .track
.startup-stage  |  .table-row-cell:hover
.tab-pane .tab  |  .tab-pane .tab:focused, .tab-pane .tab:selected:focused
.tab-pane .tab:hover  |  .tab-pane .tab:selected
.tab-pane .tab-label  |  .tab-pane > .tab-header-area
.tab-pane > .tab-header-area > .headers-region > .tab  |  .tab-pane > .tab-header-area > .tab-header-background
.tab-pane:focused, .tab-pane .tab:focused, .tab-pane .tab:selected:focused, .tab-pane > .tab-header-area > .headers-region > .tab:focused  |  .tag
.text-field  |  .tooltip
.top-bar  |  .tree-arrow
.tree-cell  |  .tree-cell > .tree-disclosure-node > .arrow
.tree-cell:filled:hover  |  .tree-cell:filled:selected
.tree-cell:filled:selected > .tree-disclosure-node > .arrow  |  .tree-path
.tree-root-group  |  .tree-root-header
.tree-root-header:hover  |  .tree-root-name
.tree-view  |  .tree-view .tree-cell
.tree-view .tree-cell:selected  |  .window-button
.window-button:hover  |  .window-close
.window-close:hover
```

How to inspect the default values: open `src/main/resources/styles/mcl-theme.css` (or unzip
`styles/mcl-theme.css` from the launcher jar) — every selector above has a rule there you can copy and
adjust.

---

## 简体中文

### 1. 主题能改什么

主题就是**叠在启动器默认样式之上的一份 CSS**，外加几个由启动器自己处理的声明式选项。可以改：

| | 例子 |
|---|---|
| 颜色 | 窗口底色、顶栏、卡片、按钮、文字、边框、标签 |
| 装饰 | 圆角、边框粗细、阴影（`-fx-effect`）、渐变 |
| 字体 | 字号、字重、字体族（只能用已安装的字体） |
| 背景 | 一张图、怎么铺、压暗多少 |
| 元数据 | 名称（多语言）、作者、版本、说明、缩图 |

不能改布局结构、不能加新控件、不能引用远端资源、不能带字体（`@font-face` 会被拒绝）。
主窗口和**所有对话框**共用同一套样式链，所以改了就一起变。

> **重点：颜色走「设计变量」。** 启动器里所有颜色（含 ComboBox / Spinner / CheckBox / ScrollBar /
> TabPane / ContextMenu 这些原生控件）都来自 40 个 `-brisk-*` 变量，集中定义在
> `mcl-theme.css` 的 `.root` 里。主题通常**只要覆盖这些变量**，不用一个个找选择器。
> 完整清单见英文段 **§15 Appendix A**。
>
> **启动动画也会跟着主题变。** 双击后立刻出现的那个小窗口用的是同一套变量，
> 并且有自己的 `.splash-*` 类；想改它的样子就覆盖 `.splash-root`（整面底色）、`.splash-card`、`.splash-ring`、
> `.splash-bolt`、`.splash-title`、`.splash-status`、`.splash-track`、`.splash-bar`。
>
> JavaFX 起来之前由 JVM 画的那张图（启动器旁边的 `splash.gif`）也是用同一套变量生成的，
> 所以颜色一致：第一次以某个主题启动时会重写，之后每次换主题也会重写；
> 安装包里自带的那张是「默认主题」版本（只在第一次启动前用到）。

### 2. 五分钟上手

1. 把 `examples/my-theme/` 整个复制到 `data/themes/`（资料夹名就是主题 id）；
2. 打开**设置 → 外观 → 主题包**选它，切换即时生效，不用重启；
3. 改 `theme.css` 后按**重新扫描主题**，或切到别的主题再切回来。

内建主题是最好的起点：`data/themes/night/theme.css` 是深色范例、`sakura` 是浅色、
`contrast` 演示加粗边框与高对比文字。

### 3. 资料夹结构

```
data/themes/my-theme/
  theme.css            必需（主样式）
  extra.css            可选（可以有很多份 *.css）
  theme.json           可选：元数据与选项（建议写）
  pack.properties      可选（旧格式）：name / author / version
  background.png       可选背景图（png / jpg / jpeg / webp）
  logo.png             可选品牌图（顶栏 + 窗口/任务栏图标）
  preview.png          可选缩图，显示在设置页
```

资料夹名就是主题 id：建议只用 `[A-Za-z0-9._-]`。`default` 是内建主题，不要占用。

### 4. `theme.json` 字段

```json
{
  "name": { "zh_CN": "示例：午夜紫", "zh_TW": "範例：午夜紫", "en_US": "Sample: Midnight Purple" },
  "description": { "zh_CN": "一行说明", "en_US": "One line" },
  "author": "you",
  "version": "1.0.0",
  "homepage": "https://example.com",
  "base": "dark",
  "accent": "#B79CFF",
  "styles": ["theme.css", "extra.css"],
  "background": "background.png",
  "backgroundFit": "cover",
  "backgroundDim": 0.35,
  "preview": "preview.png"
}
```

| 字段 | 类型 | 预设 | 说明 |
|---|---|---|---|
| `name` | 字串或 `{语言: 字串}` | 资料夹名 | 下拉选单显示的名字；多语言物件会先找当前界面语言，再 `zh_CN`、`en_US`、最后一个。 |
| `description` | 字串或物件 | `""` | 显示在下拉选单下面。 |
| `author` / `version` | 字串 | `""` | 附在下拉名字后面并作为元数据显示。 |
| `homepage` | 字串 | `""` | 仅作说明。 |
| `base` | `light` / `dark` | `light` | 只是一个提示：设置页会显示 🌙/☀️，外挂可经 `context.theme().dark()` 读取；没写 `backgroundDim` 时深色预设 0.25。 |
| `accent` | `#RRGGBB` | `""` | 主题主色，开放给外挂配色用。 |
| `styles` | 档案名阵列 | 全部 `*.css` | 照这个顺序叠加；不写就是 `theme.css` 第一、其余按档名。路径不能跳出主题资料夹。 |
| `background` | 档案名 | 自动找 `background.*` | 背景图。 |
| `backgroundFit` | `cover` / `contain` / `repeat` / `center` | `cover` | 背景怎么铺。 |
| `backgroundDim` | 0–1 数字 | `base == dark ? 0.25 : 0` | 背景压暗，保证文字可读。 |
| `logo` | 档案名 | 自动找 `logo.*` | **品牌图**：显示在顶栏左侧（约 30px），同时作为窗口/任务栏图标跟着主题走。建议正方形 PNG、128–256px、透明底，缩到 26px 仍要看得清（别画细线和文字）。没有就回退到内建的樱花粉方块。 |
| `preview` | 档案名 | 自动找 `preview.*` | 设置页缩图（缩到 132×74）。 |
| `wordmark` | 布尔 | `true` | 顶栏是否显示「Brisk」大字。旁边的小字「Brisk Launcher」一律保留，这项只管大字。 |

`pack.properties`（旧格式）仍然读 `name=` / `author=` / `version=`，但它表达不了 `styles`
与背景选项。

### 5. CSS 是怎么套上去的

1. `styles/mcl-theme.css`（内建预设，**所有设计变量都在这里定义**）；
2. 你的 `styles` 依序叠加（同优先度时后盖前）。

**推荐写法：只覆盖设计变量** —— 十来行就够，整个启动器（含对话框与原生控件）一起变：

```css
.root {
    -brisk-bg: #14101F;          /* 窗口 / 页面底色 */
    -brisk-surface: #1E1830;     /* 卡片、输入框、列表、按钮 */
    -brisk-border: #332A4D;
    -brisk-text: #EDE8FA;
    -brisk-accent: #B79CFF;      /* 强调色 / 焦点框 */
    -brisk-on-accent: #14101F;   /* 强调色底上的文字 */
}
```

只改你在意的，其它保持默认。要调圆角、边框粗细、间距或个别特例，再用选择器覆盖：

```css
.card      { -fx-border-width: 1; -fx-effect: none; }
.nav-button { -fx-font-weight: bold; }
```

### 6. 背景图

* 格式 PNG / JPG / JPEG / WEBP，**上限 16 MB**。
* 固定在窗口最底层，内容区仍保留自己的底色（所以卡片和列表不会糊在图上）。
* 四角圆角、压暗都由启动器处理（`backgroundDim`）。
* 大图建议 1920×1080 以内；4K PNG 主要是在拖慢启动。

### 7. 缩图

`preview.png`（png/jpg/jpeg/webp 都行）会在设置页缩成 132×74 显示，也是「这是个深色主题」的
最省事提示。建议压在几百 KB 以内。

### 8. 安全规则（什么会被拒绝）

主题是「别人给的档案」，所以有风险的会直接不加载（设置页显示警告）：

* CSS 里 `url(...)` 出现 `http:` / `https:` / `file:` / `jar:` 或 `//`（隐私信标、探测本机档案）；
* 含 `@font-face`；
* 单档超过 2 MB；
* `styles` 里的路径跳出主题资料夹（例如 `"../x.css"`）。

被拒绝时主题**仍会留在清单里并显示警告**，不会静悄悄失效 —— 主题看起来没生效时先看
**设置 → 外观**。

### 9. 性能

* 主题清单、通过检查的 CSS、背景图都按「资料夹 + 档案（大小 / 修改时间）」快取，
  只有真的变了才重扫。
* 所有对话框共用同一条样式链，不会每个弹窗重读你的档案。
* CSS 建议控制在几千行以内、少用带动画的 `-fx-effect`；JavaFX 只解析一次 CSS，
  真正贵的是列表上逐节点套效果。
* 背景图只在切主题时解码一次，不是每页解码。

### 10. 多语言元数据

`name` / `description` 用上面那种「语言 → 字串」的物件。键名与启动器一致：
`zh_CN`、`zh_TW`、`zh_HK`、`en_US`（自订语言包 id 也可以，但至少留 `zh_CN` 与 `en_US` 兜底）。

### 11. 发布

* 交付一个资料夹（或让使用者解压到 `data/themes/` 的 zip），**资料夹名就是主题 id**；
* 带上 `theme.json` 与 `preview.png`；改配色就升 `version`，方便使用者区分版本；
* 不要写自己资料夹以外的地方，也不要教使用者去改 `mcl-theme.css`；
* `data/themes/` 是使用者本机的档案，不参与同步。

### 12. 主题与外挂

外挂可以透过 `context.theme()` 读当前主题：

```java
PluginContext.ThemeInfo theme = context.theme();
theme.dark();        // 深色主题为 true
theme.accent();      // "#B79CFF"，主题没声明就是空字串
theme.name();        // 当前语言的名字
```

这样外挂面板可以跟着换色，而不是写死白底白字。

### 13. 有意为之的限制

* 不能改布局、不能加控件、不能只给某个窗口套主题（一个主题全局生效）。
* 不能引用远端图片/字体（`@font-face` 禁用）、不能用 `file://`。
* 主题不能翻译文字 —— 那是语言包（`lang/<id>/messages.properties`）的工作。
* `default` 不可覆盖，请自建 id。

### 14. 排查

| 现象 | 原因 / 处理 |
|---|---|
| 下拉里没有这个主题 | 不在 `data/themes/` 下、资料夹里既没有 `*.css` 也没有 `theme.json`/`pack.properties`、或占用了 `default` |
| 有列出但界面没变 | CSS 被安全检查拒绝（设置页有警告）；也检查 `theme.json` 是否 JSON 写坏（写坏只丢元数据，不影响 CSS） |
| 背景图不显示 | 内容区是不透明的，只有窗口底层能看到图；检查 `background` 指定的档名与副档名 |
| 深色主题字看不清 | 改 `-brisk-text*` 系列；也检查 `-brisk-on-accent`（强调色按钮上的文字） |
| 切换主题后控件还是薄荷绿 | 这个主题在覆盖选择器而不是 `-brisk-*` 变量 —— 改成覆盖变量（附录 A） |
| 深色主题按钮上的字看不见 | `-brisk-on-accent` 要和 `-brisk-accent` / `-brisk-accent-strong` 形成对比 |
| 警告「CSS 被安全检查拒绝」 | 去掉 `url(http…)`、`file:`、`jar:` 或 `@font-face` |
| 警告「没有可用的 CSS」 | `styles` 指的档案不存在，或全部被拒绝 |
| 改了没反应 | 按**重新扫描主题**；开着启动器改档有时资料夹时间戳不变，切走再切回来 |
| 对话框也跟着变 | 设计如此：全应用共用同一条样式链 |

### 15. 附录 A / B

- **设计变量清单（40 个，推荐从这里开始）**：英文段 **§15 Appendix A**。
- **CSS 选择器清单（149 个，需要细调时用）**：英文段 **§16 Appendix B**。
- 选择器原始规则在 `src/main/resources/styles/mcl-theme.css`，可以直接对照。

---

## 繁體中文

### 1. 主題能改什麼

主題就是**疊在啟動器預設樣式之上的一份 CSS**，外加幾個由啟動器自己處理的宣告式選項。可以改：

| | 例子 |
|---|---|
| 顏色 | 視窗底色、頂欄、卡片、按鈕、文字、邊框、標籤 |
| 裝飾 | 圓角、邊框粗細、陰影（`-fx-effect`）、漸層 |
| 字型 | 字級、字重、字型家族（只能用已安裝的字型） |
| 背景 | 一張圖、怎麼鋪、壓暗多少 |
| 中介資料 | 名稱（多語言）、作者、版本、說明、縮圖 |

不能改版面結構、不能加新控件、不能引用遠端資源、不能帶字型（`@font-face` 會被拒絕）。
主視窗和**所有對話框**共用同一條樣式鏈，所以改了全部一起變。

> **重點：顏色走「設計變數」。** 啟動器裡所有顏色（含 ComboBox / Spinner / CheckBox / ScrollBar /
> TabPane / ContextMenu 這些原生控件）都來自 40 個 `-brisk-*` 變數，集中定義在
> `mcl-theme.css` 的 `.root` 裡。主題通常**只要覆蓋這些變數**，不用一個個找選擇器。
> 完整清單見英文段 **§15 Appendix A**。
>
> **啟動動畫也會跟著主題變。** 雙擊後立刻出現的那個小視窗用的是同一套變數，
> 並且有自己的 `.splash-*` 類；想改它的樣子就覆蓋 `.splash-root`（整面底色）、`.splash-card`、`.splash-ring`、
> `.splash-bolt`、`.splash-title`、`.splash-status`、`.splash-track`、`.splash-bar`。
>
> JavaFX 起來之前由 JVM 畫的那張圖（啟動器旁邊的 `splash.gif`）也是用同一套變數生成的，
> 所以顏色一致：第一次以某個主題啟動時會被重寫，之後每次換主題也會重寫；
> 安裝包裡自帶的那張是「預設主題」版本（只在第一次啟動前用到）。

### 2. 五分鐘上手

1. 把 `examples/my-theme/` 整個複製到 `data/themes/`（資料夾名就是主題 id）；
2. 打開**設定 → 外觀 → 主題包**選它，切換即時生效，不用重啟；
3. 改 `theme.css` 後按**重新掃描主題**，或切到別的主題再切回來。

內建主題是最好的起點：`data/themes/night/theme.css` 是深色範例、`sakura` 是淺色、
`contrast` 示範加粗邊框與高對比文字。

### 3. 資料夾結構

```
data/themes/my-theme/
  theme.css            必需（主樣式）
  extra.css            可選（可以有很多份 *.css）
  theme.json           可選：中介資料與選項（建議寫）
  pack.properties      可選（舊格式）：name / author / version
  background.png       可選背景圖（png / jpg / jpeg / webp）
  preview.png          可選縮圖，顯示在設定頁
```

資料夾名就是主題 id：建議只用 `[A-Za-z0-9._-]`。`default` 是內建主題，不要佔用。

### 4. `theme.json` 欄位

```json
{
  "name": { "zh_CN": "示例：午夜紫", "zh_TW": "範例：午夜紫", "en_US": "Sample: Midnight Purple" },
  "description": { "zh_CN": "一行说明", "zh_TW": "一行說明", "en_US": "One line" },
  "author": "you",
  "version": "1.0.0",
  "homepage": "https://example.com",
  "base": "dark",
  "accent": "#B79CFF",
  "styles": ["theme.css", "extra.css"],
  "background": "background.png",
  "backgroundFit": "cover",
  "backgroundDim": 0.35,
  "preview": "preview.png"
}
```

| 欄位 | 型別 | 預設 | 說明 |
|---|---|---|---|
| `name` | 字串或 `{語言: 字串}` | 資料夾名 | 下拉選單顯示的名字；多語言物件會先找目前介面語言，再 `zh_CN`、`en_US`、最後一個。 |
| `description` | 字串或物件 | `""` | 顯示在下拉選單下面。 |
| `author` / `version` | 字串 | `""` | 附在下拉名字後面並作為中介資料顯示。 |
| `homepage` | 字串 | `""` | 僅作說明。 |
| `base` | `light` / `dark` | `light` | 只是一個提示：設定頁會顯示 🌙/☀️，外掛可經 `context.theme().dark()` 讀取；沒寫 `backgroundDim` 時深色預設 0.25。 |
| `accent` | `#RRGGBB` | `""` | 主題主色，開放給外掛配色用。 |
| `styles` | 檔名陣列 | 全部 `*.css` | 照這個順序疊加；不寫就是 `theme.css` 第一、其餘按檔名。路徑不能跳出主題資料夾。 |
| `background` | 檔名 | 自動找 `background.*` | 背景圖。 |
| `backgroundFit` | `cover` / `contain` / `repeat` / `center` | `cover` | 背景怎麼鋪。 |
| `backgroundDim` | 0–1 數字 | `base == dark ? 0.25 : 0` | 背景壓暗，保證文字可讀。 |
| `logo` | 檔名 | 自動找 `logo.*` | **品牌圖**：顯示在頂欄左側（約 30px），同時作為視窗/工作列圖示跟著主題走。建議正方形 PNG、128–256px、透明底，縮到 26px 仍要看得清（別畫細線和文字）。沒有就回退到內建的櫻花粉方塊。 |
| `preview` | 檔名 | 自動找 `preview.*` | 設定頁縮圖（縮到 132×74）。 |
| `wordmark` | 布林 | `true` | 頂欄是否顯示「Brisk」大字。旁邊的小字「Brisk Launcher」一律保留，這項只管大字。 |

`pack.properties`（舊格式）仍然讀 `name=` / `author=` / `version=`，但它表達不了 `styles`
與背景選項。

### 5. CSS 是怎麼套上去的

1. `styles/mcl-theme.css`（內建預設，**所有設計變數都在這裡定義**）；
2. 你的 `styles` 依序疊加（同優先度時後蓋前）。

**推薦寫法：只覆蓋設計變數** —— 十來行就夠，整個啟動器（含對話框與原生控件）一起變：

```css
.root {
    -brisk-bg: #14101F;          /* 視窗 / 頁面底色 */
    -brisk-surface: #1E1830;     /* 卡片、輸入框、清單、按鈕 */
    -brisk-border: #332A4D;
    -brisk-text: #EDE8FA;
    -brisk-accent: #B79CFF;      /* 強調色 / 焦點框 */
    -brisk-on-accent: #14101F;   /* 強調色底上的文字 */
}
```

只改你在意的，其它保持預設。要調圓角、邊框粗細、間距或個別特例，再用選擇器覆蓋：

```css
.card      { -fx-border-width: 1; -fx-effect: none; }
.nav-button { -fx-font-weight: bold; }
```

### 6. 背景圖

* 格式 PNG / JPG / JPEG / WEBP，**上限 16 MB**。
* 固定在視窗最底層，內容區仍保留自己的底色（所以卡片和清單不會糊在圖上）。
* 四角圓角、壓暗都由啟動器處理（`backgroundDim`）。
* 大圖建議 1920×1080 以內；4K PNG 主要是在拖慢啟動。

### 7. 縮圖

`preview.png`（png/jpg/jpeg/webp 都行）會在設定頁縮成 132×74 顯示，也是「這是個深色主題」的
最省事提示。建議壓在幾百 KB 以內。

### 8. 安全規則（什麼會被拒絕）

主題是「別人給的檔案」，所以有風險的會直接不載入（設定頁顯示警告）：

* CSS 裡 `url(...)` 出現 `http:` / `https:` / `file:` / `jar:` 或 `//`（隱私信標、探測本機檔案）；
* 含 `@font-face`；
* 單檔超過 2 MB；
* `styles` 裡的路徑跳出主題資料夾（例如 `"../x.css"`）。

被拒絕時主題**仍會留在清單裡並顯示警告**，不會靜悄悄失效 —— 主題看起來沒生效時先看
**設定 → 外觀**。

### 9. 效能

* 主題清單、通過檢查的 CSS、背景圖都按「資料夾 + 檔案（大小 / 修改時間）」快取，
  只有真的變了才重掃。
* 所有對話框共用同一條樣式鏈，不會每個彈窗重讀你的檔案。
* CSS 建議控制在幾千行以內、少用帶動畫的 `-fx-effect`；JavaFX 只解析一次 CSS，
  真正貴的是清單上逐節點套效果。
* 背景圖只在切主題時解碼一次，不是每頁解碼。

### 10. 多語言中介資料

`name` / `description` 用上面那種「語言 → 字串」的物件。鍵名與啟動器一致：
`zh_CN`、`zh_TW`、`zh_HK`、`en_US`（自訂語言包 id 也可以，但至少留 `zh_CN` 與 `en_US` 兜底）。

### 11. 發佈

* 交付一個資料夾（或讓使用者解壓到 `data/themes/` 的 zip），**資料夾名就是主題 id**；
* 帶上 `theme.json` 與 `preview.png`；改配色就升 `version`，方便使用者區分版本；
* 不要寫自己資料夾以外的地方，也不要教使用者去改 `mcl-theme.css`；
* `data/themes/` 是使用者本機的檔案，不參與同步。

### 12. 主題與外掛

外掛可以透過 `context.theme()` 讀目前主題：

```java
PluginContext.ThemeInfo theme = context.theme();
theme.dark();        // 深色主題為 true
theme.accent();      // "#B79CFF"，主題沒宣告就是空字串
theme.name();        // 目前語言的名字
```

這樣外掛面板可以跟著換色，而不是寫死白底白字。

### 13. 刻意為之的限制

* 不能改版面、不能加控件、不能只給某個視窗套主題（一個主題全域生效）。
* 不能引用遠端圖片/字型（`@font-face` 禁用）、不能用 `file://`。
* 主題不能翻譯文字 —— 那是語言包（`lang/<id>/messages.properties`）的工作。
* `default` 不可覆蓋，請自建 id。

### 14. 排查

| 現象 | 原因 / 處理 |
|---|---|
| 下拉裡沒有這個主題 | 不在 `data/themes/` 下、資料夾裡既沒有 `*.css` 也沒有 `theme.json`/`pack.properties`、或佔用了 `default` |
| 有列出但介面沒變 | CSS 被安全檢查拒絕（設定頁有警告）；也檢查 `theme.json` 是否 JSON 寫壞（寫壞只丟中介資料，不影響 CSS） |
| 背景圖不顯示 | 內容區是不透明的，只有視窗底層能看到圖；檢查 `background` 指定的檔名與副檔名 |
| 深色主題字看不清 | 改 `-brisk-text*` 系列；也檢查 `-brisk-on-accent`（強調色按鈕上的文字） |
| 切換主題後控件還是薄荷綠 | 這個主題在覆蓋選擇器而不是 `-brisk-*` 變數 —— 改成覆蓋變數（附錄 A） |
| 深色主題按鈕上的字看不見 | `-brisk-on-accent` 要和 `-brisk-accent` / `-brisk-accent-strong` 形成對比 |
| 警告「CSS 被安全检查拒绝」 | 去掉 `url(http…)`、`file:`、`jar:` 或 `@font-face` |
| 警告「没有可用的 CSS」 | `styles` 指的檔案不存在，或全部被拒絕 |
| 改了沒反應 | 按**重新掃描主題**；開著啟動器改檔有時資料夾時間戳不變，切走再切回來 |
| 對話框也跟著變 | 設計如此：全應用共用同一條樣式鏈 |

### 15. 附錄 A / B

- **設計變數清單（40 個，建議從這裡開始）**：英文段 **§15 Appendix A**。
- **CSS 選擇器清單（149 個，需要細調時用）**：英文段 **§16 Appendix B**。
- 選擇器原始規則在 `src/main/resources/styles/mcl-theme.css`，可以直接對照。

---

## License

Themes are your own work. The launcher is licensed under the terms in [LICENSE](LICENSE).
