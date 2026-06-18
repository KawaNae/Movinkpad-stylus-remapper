# Movinkpad Stylus Remapper

<p align="center">
  <img src="docs/screenshot.png" alt="Stylus Remapper スクリーンショット" width="800">
</p>

**[English version](README.md)**

Wacom MovinkPad Pro 14 の Pro Pen 3 サイドボタンをキーボードショートカットやマウスクリックにリマップする Android アプリです。

CLIP STUDIO PAINT など、スタイラスボタンのネイティブ対応が不十分なアプリでの利用を想定しています。

## 機能

- **キーボードショートカット** — 修飾キー (Ctrl, Alt, Shift) + キーの任意の組み合わせ
- **マウスボタンマッピング** — 左 / 中 / 右クリック、キーとの組み合わせ（例: 左クリック+Space でキャンバスパン）
- **プリセットギャラリー** — よく使うマッピングをワンタップで選択（Ctrl+Z, Space, Ctrl+Alt 等）
- **プロファイル** — 名前付きプロファイルを複数保存・切替（例: ClipStudio, MediBang）
- **通知表示** — 現在のプロファイル名とキー設定をステータスバーに表示
- **ライト / ダークモード** — システムのテーマ設定に自動追従
- **多言語対応 (i18n)** — 日本語・英語対応済み。`values-XX/strings.xml` を追加するだけで新言語に対応可能
- **画面回転対応** — どの向きでもマッピングが正しく動作
- **パームリジェクション** — ペンボタンのイベントのみをキャプチャ。タッチ入力には影響なし

### デフォルトマッピング

| スイッチ | 位置 | 割り当て | 用途 (CSP) |
|----------|------|----------|------------|
| Switch 1 | ペン先側 | Ctrl+Alt | ブラシサイズ変更 |
| Switch 2 | 上側 | Space | キャンバスドラッグ |
| Switch 3 | 同時押し | Ctrl+Z | 元に戻す |

## 必要なもの

- Wacom MovinkPad Pro 14 (Android 14)
- [Shizuku](https://shizuku.rikka.app/) アプリ（インストール済み＆起動済み）
- 初回セットアップ時のみ ADB 接続（ワイヤレスデバッグまたは USB）

## ダウンロード

[Releases ページ](https://github.com/KawaNae/Movinkpad-stylus-remapper/releases/latest) から APK をダウンロードしてインストールできます。

## セットアップ

### 1. Shizuku の準備

1. [Shizuku](https://shizuku.rikka.app/download/) をインストール
2. 以下のいずれかで Shizuku を起動:
   - **ワイヤレスデバッグ** (Android 11+): 開発者オプション → ワイヤレスデバッグを有効 → Shizuku アプリから起動
   - **ADB 経由**: PC から `adb shell sh /sdcard/Android/data/moe.shizuku.privileged.api/start.sh`

### 2. アプリのインストール

[Releases](https://github.com/KawaNae/Movinkpad-stylus-remapper/releases/latest) から APK をダウンロードしてインストール。

ソースからビルドする場合:
```bash
./gradlew assembleDebug
adb install app/build/outputs/apk/debug/MovinkpadStylusRemapper-v3.1.1.apk
```

### 3. 使い方

1. Shizuku が起動していることを確認
2. Stylus Remapper を開く
3. Shizuku の権限ダイアログで「許可」
4. 接続時に自動で開始されます — ステータスバッジをタップで開始/停止

## 仕組み

- `/dev/input/event*` から Linux の生入力イベント（`input_event` 構造体）を読み取り
- Pro Pen 3 のサイドボタン押下を検出（`EV_KEY`: `0x14b`, `0x14c`）
- フルプロキシ方式: `EVIOCGRAB` でデバイスを排他取得し、ボタン以外のイベントは再注入、マッピング済みキー/マウスイベントを `InputManager.injectInputEvent()` で注入
- Shizuku の UserService として特権プロセスで動作（ADB shell 相当の権限）
- ネイティブライブラリ `libpengrab.so` が低レベルの `EVIOCGRAB` 操作を担当

## プロジェクト構成

```
app/src/main/
├── aidl/.../IRemapperService.aidl     # IPC インターフェース
├── cpp/pengrab.c                      # ネイティブ EVIOCGRAB ヘルパー
├── java/.../
│   ├── MainActivity.java              # UI（プロファイル・ボタン設定・開始/停止）
│   ├── RemapperUserService.java       # コアロジック（Shizuku 特権プロセス）
│   ├── RemapperForegroundService.java # 常駐通知
│   ├── ShizukuHelper.java            # Shizuku 権限・バインディング管理
│   ├── PenGrab.java                  # libpengrab.so の JNI ブリッジ
│   ├── MappingPresets.java           # プリセット定義
│   ├── KeyDefinitions.java          # キー一覧・表示名ユーティリティ
│   ├── ProfileManager.java          # プロファイル保存・読込管理
│   └── ButtonAction.java            # マッピングデータモデル（Parcelable）
└── res/
    ├── layout/                       # UI レイアウト
    ├── drawable/                     # アイコン・背景
    ├── values/                       # 英語文字列・カラー・テーマ
    ├── values-ja/                    # 日本語文字列
    └── values-night/                 # ダークモード用カラー・テーマ
```

## 注意事項

- Shizuku は再起動のたびに再起動が必要です（root なしの場合）
- ボタンのイベントコードは MovinkPad Pro 14 + Pro Pen 3 の実測値です。他デバイスでは異なる可能性があります
- キーマッピングはアプリ内で自由にカスタマイズ可能です

## ビルド環境

- Android Studio
- compileSdk 34 / minSdk 26 / targetSdk 34
- Shizuku API 13.1.5
- Java 11
- CMake 3.22.1（ネイティブコード用）

## ライセンス

MIT
