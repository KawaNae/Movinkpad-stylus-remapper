# 「空中キャンバスパン」実現可否の検証 (2026-06-17)

GitHub Issue #1: ペンをホバーさせたまま、サイドボタンでキャンバスをパンしたい
（Wacom デスクトップドライバの「左クリック+Space」相当）。

Wacom MovinkPad Pro 14 (Android 14) 実機 + Shizuku(shell) 相当ランタイムで検証した結論と、
そこに至る全経路の記録。**結論: 実現可能。ただし EVIOCGRAB によるペン入力の所有が必須。**

## 環境前提
- ペンのサイドボタン押下/ホバー座標は `/dev/input/event6`（Wacom HID Pen）から読める（read 可）。
- キーイベント注入（`InputManager.injectInputEvent`）は効く（既存アプリの仕組み）。
- 表示は ROTATION_270。物理 1800x2880 / 論理 2880x1800。

## 試した注入経路と結果

| 経路 | 描画(個別) | パン(単一アンカー) | 備考 |
|------|-----------|------------------|------|
| `injectInputEvent` MotionEvent(touch) | キャンバス✗ / UIメニュー○ | ✗ | CSP キャンバスは TOUCHSCREEN source を無視 |
| 実 `event5` への raw write | — | — | SELinux で Permission denied (read のみ可) |
| uhid 相対マウス | ✗ | ✗ | カーソルモードに落ち、ペン専用機はカーソル抑制 |
| uhid 単指デジタイザ | ✗ | ✗ | hid-generic 止まりで INPUT_PROP_DIRECT 立たず→MOUSE/POINTER 扱い |
| uhid マルチタッチ(Win8準拠) | ○(P:1/1) | △ | DIRECT タッチ成立。2本指で**パン可(ペン非在圏時)** |
| `injectInputEvent` stylus | ○ | △ | キャンバスに描画/パン可(**ペン非在圏時**)。InputReader を迂回 |
| uhid スタイラス | ○ | △ | injectInputEvent stylus と同挙動 |

## 核心的な制約: in-range スタイラスがパン基準を独占する

- CSP の描画は **ポインタ個別** の操作 → 別ポインタ(injected/uhid stylus)でもホバー中の実ペンと無関係に描ける。
- CSP のパン(ハンドツール/Space)は **単一の主ポインタ** に従い、それを **in-range の実ペンが独占** する。
- このため、実ペンがホバー(in-range)している間は、
  - injected stylus でも、**実マウス+キーボードの左クリック+Space でも**パンできない（実機で確認）。
  - ソース種別(mouse/stylus/touch)や buttonState を変えても回避不可。
- 加えて uhid タッチは、ペン在圏中 **システムレベルのパームリジェクションで全抑制**(pointer_location が P:0/0)。

→ Win/Mac の Wacom ドライバが「単純に tip-up を down に書き換える」だけで済むのは、
ドライバがペンデバイスを**所有**し唯一の権威だから。Android では傍観者の注入は実ペンに負ける。

## 唯一の道: EVIOCGRAB でペンを所有する（検証済み・成功）

`ioctl(fd, EVIOCGRAB, 1)` で `/dev/input/event6` を排他奪取すると：
- **SELinux は shell の EVIOCGRAB を許可**（grab/release 成功）。
- **grab した瞬間、Android は実ペンを out-of-range 扱いにする**（in-range のまま凍結はしない）。
- その状態で `Space 保持 + injected stylus のドラッグ` → **パン成立**（実機で 26.8% の画面変化を確認。
  grab 無しの同条件は 0.08% でパン不成立 → grab が決定要因）。

### 実装方式（確定）
1. サイドボタン押下中だけ event6 を `EVIOCGRAB`（実ペンを Android から隔離）。
2. grab した fd から実ペンのホバー座標(ABS_X/Y)を読む。
3. 表示回転を考慮して、合成スタイラス（uhid or injectInputEvent）を同座標に tip-down で注入 + Space 保持。
4. ペン移動に追従して合成スタイラスを動かす → キャンバスがペンに追従してパン。
5. ボタン解放で tip-up / Space-up / `EVIOCGRAB` 解放（ペンは通常動作に復帰）。

### リスク / 注意
- ネイティブ ioctl が必要（Java/app_process の `android.system.Os` では呼べない）。NDK でビルド
  （`clang --target=aarch64-linux-android21`）。grab.c 参照。
- grab はパン中(ボタン押下中)だけ。通常描画時はペンに触れない。
- **プロセス死亡時は fd クローズで grab 自動解放** → ペンが永久に無反応になる事故は回避される。
- 座標マッピングと追従の滑らかさ・遅延は品質課題（実現可否ではない）。
- MovinkPad 系専用前提なので端末差は基本的に無視できる。

## このディレクトリのファイル
- `grab.c` — EVIOCGRAB の最小実装（`grab <device> [holdSec]`）。決定的検証に使用。
- `UhidPen.java` — uhid 仮想スタイラス（Tip Switch + In Range + X/Y, bus=I2C）。
- `UhidTouch.java` — uhid Win8 マルチタッチ（2本指パンの代替経路）。
- `KeyHold.java` — `injectInputEvent` でキー(Space)を保持。
- `UhidSpike.java` / `UhidDigitizer.java` — 失敗経路（相対マウス / 単指）。記録用。
- `diff.py` — スクショ前後のピクセル差分（客観判定の検証ハーネス, 要 Pillow）。

ビルド例:
```
javac --release 11 -cp android.jar -d build <File>.java
d8 --output out.jar --lib android.jar --min-api 26 build/<Class>.class
CLASSPATH=/data/local/tmp/out.jar app_process /system/bin <Class> <args>
```
