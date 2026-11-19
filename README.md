# XREAL 1S + Eye Recorder (Android)

XREAL 1S と XREAL Eye を Android スマートフォンに USB 接続し、Eye のカメラ映像とマイク音声をスマートフォンに録画するアプリです。

USB ネットワーク（NCM/ECM）経由の TCP HEVC を主経路とし、UVC HEVC にも対応します。グラスから受信した HEVC 映像を再エンコードせずに保存します。

## ダウンロード

**[正式版 v1 — APK をダウンロード](https://github.com/SyameimaruKoa/XREAL-1S_Eye-Capture-android/releases/latest/download/xreal-recorder.apk)**

[リリース一覧](https://github.com/SyameimaruKoa/XREAL-1S_Eye-Capture-android/releases)

## スクリーンショット

| メイン画面 | 録画中 | 画面保護モード |
| :---: | :---: | :---: |
| 掲載予定 <!-- <img src="docs/screenshots/main.png" width="240" alt="メイン画面"> --> | 掲載予定 <!-- <img src="docs/screenshots/recording.png" width="240" alt="録画中"> --> | 掲載予定 <!-- <img src="docs/screenshots/screen-saver.png" width="240" alt="画面保護モード"> --> |
| **画質選択** | **保存モード選択** | **本体・UVC 設定** |
| 掲載予定 <!-- <img src="docs/screenshots/quality.png" width="240" alt="画質選択"> --> | 掲載予定 <!-- <img src="docs/screenshots/save-mode.png" width="240" alt="保存モード選択"> --> | 掲載予定 <!-- <img src="docs/screenshots/device-settings.png" width="240" alt="本体・UVC 設定"> --> |

### XREAL 第2画面

| 固定表示（右上） | サイド表示（中央・拡大） |
| :---: | :---: |
| 掲載予定 <!-- <img src="docs/screenshots/xreal-fixed.png" width="440" alt="XREAL 固定表示"> --> | 掲載予定 <!-- <img src="docs/screenshots/xreal-side.png" width="440" alt="XREAL サイド表示"> --> |

## 主な機能

- **HEVC パススルー録画** — TCP / UVC で受信した映像を再エンコードせずに保存。
- **画質切替** — 広い画角の 720p/30、高画質の 1600p/30、高フレームレートの 1600p/60、UVC HEVC を選択。
- **4種類の保存モード** — 通常の MP4、録画停止後の MP4 変換、断片化 MP4（fMP4）、生データ保存。
- **USB マイク録音** — UAC で取得した音声を AAC モノラルで保存。入力波形と音声状態を表示し、音声取得に失敗しても映像録画を継続。
- **XREAL 第2画面オーバーレイ** — 固定表示（右上）、サイド表示（中央・拡大）、通常の画面複製を選択。録画状態、経過時間、音声状態を表示。
- **バックグラウンド録画・画面保護** — スマートフォンの消灯中も録画を継続。点灯を維持する場合は焼き付き対策の画面保護モードを使用可能。
- **フレーム時刻の安定化** — カメラ時刻を PTS に使用し、不正な時刻を必要に応じて補正。TCP の受信バッファでバースト到着による間隔の乱れを低減。
- **録画状態の監視** — 映像受信停止、受信レート、音声入力状態を表示。保存先も選択可能。
- **本体・UVC 設定** — グラスの状態確認、UVC の有効化、画質設定・中断した設定の復旧。
- **Material 3 UI** — ダークテーマとダイナミックカラーに対応。

## 対応画質

| モード | 接続方式 | 解像度 | フレームレート | 実測ビットレート |
| --- | --- | --- | --- | --- |
| 720p/30（広い画角） | TCP HEVC | 1280 × 720 | 約30 fps | 約17 Mbps |
| 1600p/30（高画質） | TCP HEVC | 1600 × 1200 | 30 fps（設定値） | — |
| 1600p/60（高レート） | TCP HEVC | 1600 × 1200 | 約59 fps | 約34 Mbps |
| UVC HEVC | USB UVC | 2048 × 1512 | 約59 fps | 約2 Mbps |

※ アプリ内の画質名に合わせて記載しています。UVC は TCP と画角が異なり、実測ビットレートも低いため、通常は TCP を推奨します。

## 保存モード

| モード | 動作 |
| --- | --- |
| MP4 同時変換（標準） | 録画しながら HEVC + AAC の MP4 を作成。 |
| 生保存 → 停止時に MP4 変換（保全） | 生データを保存し、録画停止時に MP4 化。中断された未変換録画は次回起動時に復旧を試行。 |
| fMP4（断片化 MP4・保全） | 断片単位で MP4 に書き込み、録画中断時のデータ保全性を高める。初期設定の保存モード。 |
| 生データのみ（無変換） | MP4 化せず、受信した映像・音声のデータを保存。 |

診断用の HEVC・WAV を別途保存することもできます。

## 使い方

1. リリースから APK をダウンロードして Android スマートフォンにインストール。
2. XREAL 1S と XREAL Eye を接続し、スマートフォンと USB で接続。
3. 必要な権限を許可し、画質・保存モード・保存先・第2画面表示を選択。
4. 「録画開始」を押し、録画終了時は「録画停止」を押す。

UVC HEVC を使用する場合は、必要に応じて「本体・UVC 設定」から UVC を有効化してください。

## 保存先

通常は `DCIM/100XREAL/VID_yyyyMMdd_HHmmss.mp4` に保存します。標準保存先を使用した場合、同名の JSON メタデータは `Documents/XREAL/100XREAL` に保存されます。保存先はアプリ内から変更できます。同名ファイルが存在する場合は上書きせず録画を開始しません。

## 既知の制限

- TCP 経路は実機で長時間録画を検証済みです（626秒 / 18,701フレーム、全フレーム復号エラーなし）。
- TCP の高解像度モードは 720p/30 より左右の画角が狭くなります。60 fps の再起動なしの切替はグラス側の root 権限を使用する試験機能で、切替時の一時的な表示途切れや長時間の安定性は未確認です。非 root の切替方法と 1280×720 / 60 fps の再現方法は未解決です。再起動を伴う準備操作では DP 映像が消える場合があります。
- UAC 音声はグラス側で左右同一のモノラル信号に複製されているため、AAC 1ch で保存します。独立したステレオ録音にはグラス側のファームウェア変更が必要です。静かな環境では音声が小さい旨の警告が表示される場合がありますが、音声は保存されます。
- 録画開始後は画面消灯中も録画を継続できますが、手動ロック・スリープ時の XREAL 第2画面の維持は未解決です。また、画面消灯中は開発用の `DEV_CMD` を受信できません。
- 時刻補正や受信バッファによって、受信の揺れやフレーム欠落を完全に解消できるわけではありません。

## ビルド

Android SDK（build-tools 35.0.0、platform android-35）と JDK 21 が必要です。

Windows:

```powershell
powershell -ExecutionPolicy Bypass -File scripts\build.ps1
```

Linux / macOS:

```bash
scripts/build.sh
```

APK は `build/xreal-recorder.apk` に生成されます。署名用キーストアは `.secrets/local.keystore`（リポジトリ外・初回生成）を使用します。

ビルドした APK のインストール:

```bash
adb install -r build/xreal-recorder.apk
```

## ドキュメント

調査記録と引継ぎは [docs/](docs/) にあります。開発の最新状態は [docs/HANDOFF_CURRENT.md](docs/HANDOFF_CURRENT.md) を参照してください。

## ライセンス

未設定。
