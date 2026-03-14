# Movinkpad Stylus Remapper

Wacom MovinkPad Pro 14 の Pro Pen 3 サイドボタンをキーボードショートカットにリマップする Android アプリです。

CLIP STUDIO PAINT など、スタイラスボタンのネイティブ対応が不十分なアプリでの利用を想定しています。

## 機能

- **カスタムキーコンビネーション**: 修飾キー0〜3個 (Ctrl, Alt, Shift) + 文字/ファンクションキー0〜1個の任意の組み合わせ
- **プロファイル機能**: 名前付きプロファイルを複数保存し、一括切替（例: ClipStudio, MediBang）
- **プリセット**: よく使うキー設定をワンタップで選択（Ctrl+Alt, Space, Ctrl+Z 等）
- **通知表示**: 現在のプロファイル名とキー設定をステータスバーに表示

### デフォルトマッピング

| ボタン | 位置 | 割り当て | 用途 (CSP) |
|--------|------|----------|------------|
| Button 1 | ペン先側 | Ctrl+Alt | ブラシサイズ変更 |
| Button 2 | 中央 | Space | キャンバスドラッグ |
| Button 3 | ペン先から遠い側 | Ctrl+Z | 元に戻す |

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
adb install app/build/outputs/apk/debug/MovinkpadStylusRemapper-v2.0.apk
```

### 3. 使い方

1. Shizuku が起動していることを確認
2. Stylus Remapper を開く
3. Shizuku の権限ダイアログで「許可」
4. 「Start」ボタンをタップ

## 仕組み

- `/dev/input/event6` から Linux の生入力イベント（`input_event` 構造体）を読み取り
- Pro Pen 3 のサイドボタン押下を検出（`EV_KEY`: `0x14b`, `0x14c`）
- `InputManager.injectInputEvent()` でキーイベントを注入
- Shizuku の UserService として特権プロセスで動作（ADB shell 相当の権限）

## プロジェクト構成

```
app/src/main/
├── aidl/.../IRemapperService.aidl    # IPC インターフェース
├── java/.../
│   ├── MainActivity.java             # UI（プロファイル・ボタン設定・Start/Stop）
│   ├── RemapperUserService.java      # コアロジック（Shizuku 特権プロセス）
│   ├── RemapperForegroundService.java # 常駐通知（設定表示）
│   ├── ShizukuHelper.java            # Shizuku 権限・バインディング管理
│   ├── MappingPresets.java           # プリセット定義
│   ├── KeyDefinitions.java           # キー一覧・表示名ユーティリティ
│   └── ProfileManager.java           # プロファイル保存・読込管理
└── res/                              # レイアウト・リソース
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

## ライセンス

MIT
