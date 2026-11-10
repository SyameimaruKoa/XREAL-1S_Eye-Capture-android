# XREAL 1S + Eye キャプチャ＆レコーダー (Android)

XREAL 1S と XREAL Eye を Android スマートフォンに USB 接続し、Eye のカメラ映像と音声を録画するアプリです。
TCP（USB 上の NCM/ECM ネットワーク）経由の HEVC 受信を主経路とし、UVC / MJPEG 経路も選択できます。

## 主な機能

- **TCP HEVC 録画（既定・最優先）** — XREAL Eye の RGB カメラストリームを MP4（HEVC + AAC）として保存。通常設定では 1280×720 / 約30 fps / 約17 Mbps の実測例があります。
- **フレーム時刻の安定化** — フレームヘッダー offset 24 のカメラ時刻を PTS に採用し、必要に応じて不正な時刻を補正。受信の揺れやフレーム欠落を解消する機能ではありません。
- **XREAL 第2画面オーバーレイ** — 固定表示（右上）とサイド表示（中央・拡大）に対応。録画中であること、経過時間、音声状態を表示。
- **録画停止の防止** — 消灯中の継続、省電力対象外、焼き付き対策、音声取得失敗時も映像録画を継続。
- **音声状態の可視化** — 完全ゼロと小さい非ゼロを区別して表示。

## 現在の状態と既知の制限

- TCP 経路は実機で長時間録画を検証済み（626 秒 / 18,701 フレーム、全フレーム復号エラーなし）。
- **TCP 60 fps 準備は試験機能**。グラス側の root 権限で一時設定を適用して本体を再起動します。1600×1200 で約59.33 fps・32.76 Mbps の約5分49秒の録画例がありますが、720p より左右の画角が狭く、再起動後に DP 映像が消える場合があります。USB-C の抜き差しで一時設定が失われる場合もあります。非 root・再起動不要の切り替えは未解決です。
- 過去の `VID_0022.mp4` は TCP HEVC 1280×720、約59.44 fps・34.03 Mbps で保存されましたが、この状態の発生条件と再現方法は未確認です。
- 音声が非常に小さい問題は未解決（平均 -82 dBFS 前後）。音声トラックの存在のみを録音成功の根拠にはしていません。
- UVC / MJPEG 経路は実装済みだが試験中。TCP を優先してください。

## ビルド

Android SDK（build-tools 35.0.0、platform android-35）と JDK 21 が必要です。

```powershell
powershell -ExecutionPolicy Bypass -File scripts\build.ps1
```

Linux / macOS:

```bash
scripts/build.sh
```

APK は `build/xreal-recorder.apk` に生成されます。署名用キーストアは `.secrets/local.keystore`（リポジトリ外・初回生成）を使用します。

## インストール

```bash
adb install -r build/xreal-recorder.apk
```

## 保存先

通常は `DCIM/100XREAL/VID_yyyyMMdd_HHmmss.mp4`。標準時の同名 JSON は `Documents/XREAL/100XREAL` に保存されます。MJPEG は `.mkv` です。同名ファイルが存在する場合は上書きせず録画を開始しません。

## ドキュメント

調査記録と現在の引継ぎは `docs/` にあります。最新の状態は `docs/HANDOFF_CURRENT.md` を参照してください。

## ライセンス

未設定（必要に応じて追加してください）。
