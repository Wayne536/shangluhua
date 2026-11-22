# 入库助手（商陆花 × 微信笔记）

> **当前可用的稳定版是「网页版」（Python）**，见 [docs/WEB_VERSION.md](docs/WEB_VERSION.md)。
> Android App 版还在开发中，暂不可用。

把微信群里发的商品笔记，一条条录进商陆花的货品档案。

## 两条实现路线

### 1. Python 接口版（已稳定可用）
在 Termux/proot 环境里跑，通过商陆花 HTTP 接口直接建货品。

- `api_import.py` — 接口封装（登录会话、查颜色尺码、建货品、传图）
- `note_parser.py` — 笔记解析（款号/名称/颜色/尺码）
- `session.py` — 抓取商陆花的 sessionid（本地 mitmproxy + 手机代理）
- `entry.py` — 手机浏览器打开的网页入口（批量队列、实时进度）
- `ocr.py` — 从图片识别颜色（已停用，太慢）

用法：`python3 entry.py`，浏览器开 `http://127.0.0.1:5055`

### 2. Android App 版（开发中）
`src/` 下是原生 Java 实现的 App（`com.slh.app`），不依赖电脑。

- 用 Shizuku 获得 shell 权限，执行 `am start --windowingMode 5` + `am task resize`
  把微信、商陆花、本面板摆成固定三段
- 屏幕捕获（MediaProjection 单个应用）用于嵌入式预览
- Shizuku 注入点击（`input tap`）

## 构建（在手机上的 Ubuntu 环境）

```bash
cd slh-app
./build.sh     # 产出 build/app.apk
adb install -r build/app.apk
```

依赖：JDK 17、aapt2（build-tools 29）、D8（r8.jar）、API 30 的 android.jar、
Shizuku 的 api/aidl/provider 三个 aar（已放在 `libs/`）。

## 已知限制

- 微信设置了 FLAG_SECURE，**无法截屏/捕获画面**，所以微信只能用真实窗口
- 商陆花是明文 HTTP，会话靠抓包获取，App 登录会顶掉手机端登录
- OPPO 会定期杀 Shizuku，需要手动拉起
