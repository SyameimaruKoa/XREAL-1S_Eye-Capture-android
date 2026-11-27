# XREAL 1S Eye Capture (Android)

XREAL 1S（XREAL Eye 搭載グラス）の映像・音声・ジャイロ（IMU）データを、Android 端末単体で録画・記録するためのネイティブアプリです。

​<img src="docs/images/screenshot-main.png" width="320" alt="メイン画面（待機中）">

## 主な特徴

- **2系統の映像受信に対応**: USB Ethernet 経由の TCP ストリーム（`169.254.2.1`）と、USB Host API を用いた UVC Bulk 転送（`2048×1512`）の両方に対応しています。
- **クラッシュセーフな MP4 録画**: Gyroflow 公式の FFmpeg（`libavformat`）をベースにしたネイティブライブラリ（`libgyroflow_inject.so`）で Fragmented MP4（`ftyp + moov + moof + mdat`）を直接書き出します。録画中にアプリが強制終了しても、直前までの映像・音声・ジャイロデータが破損せず再生できます。
- **Gyroflow 連携（`gyrf` トラック内蔵）**: 録画中にグラスの IMU データを 3 秒ごとに MP4 内の `gyrf` トラックへ追記するため、撮影後の動画をそのまま Gyroflow（`gyro2bb` 等）で手ブレ補正できます。
- **USB オーディオ録音と波形表示**: XREAL USB オーディオデバイスを優先的に選択し、48 kHz / 32-bit PCM で集音します。MP4 内には AAC-LC（48 kHz / モノラル / 128 kbps）で格納しつつ、解析用のステレオ 32-bit PCM WAV サイドカーも出力します。
- **外部ディスプレイ・省電力対応**: 外部ディスプレイ出力（`Presentation`）でのリアルタイム音声波形・ステータス表示や、長時間録画向けのスクリーンセーバー／画面常時点灯の切り替えに対応しています。

## 映像ルート・画質モード

| モード名 | 受信方式 | 仕様・特徴 |
| :--- | :--- | :--- |
| **720p/30 (広い画角)** | TCP over USB Ethernet | 1280×720 / 30 fps の広画角 HEVC ストリーム |
| **1600p/30 (高画質)** | TCP over USB Ethernet | 高解像度 30 fps の HEVC ストリーム |
| **1600p/60 (高レート)** | TCP over USB Ethernet | 1600×1200 / 60 fps の高フレームレート HEVC ストリーム |
| **UVC HEVC** | USB UVC Bulk 転送 | 2048×1512（IF10 alt 0 / EP `0x89`）の UVC HEVC ストリーム |

> **保存方式の切り替え**: デフォルトの fMP4 単一書込み（`SAVE_MODE_FMP4`）のほか、生ストリーム保存後に録画停止時へ MP4 変換する方式や、生データのみ保存するモードも利用できます。

## 動作要件

- **OS**: Android 11（API 30）以上（`minSdk 30` / `targetSdk 35`）
- **ハードウェア**: USB Host（OTG）に対応した `arm64-v8a` 端末（`android.hardware.usb.host` 必須）
- **必要な権限**: マイク（`RECORD_AUDIO`）、カメラ（`CAMERA`）、通知（`POST_NOTIFICATIONS`）、フォアグラウンドサービス（接続デバイス・マイク）

## 使い方

1. [Releases](https://github.com/SyameimaruKoa/XREAL-1S_Eye-Capture-android/releases) から最新の `xreal-recorder.apk` をダウンロードし、Android 端末にインストールします。
2. XREAL 1S を USB-C で端末に接続し、表示される USB デバイスおよびマイク・通知の権限を許可します。
3. UVC 経路を使う場合は画面上の「UVC 有効化」ボタンから USB HID 経由で有効化し、使用する画質モードを選択します。
4. 録画開始ボタンで録画を開始し、終了時は停止ボタンでファイルを確定します。

### 保存先と出力ファイル

- **ファイル名規則**: `VID_yyyyMMdd_HHmmss.mp4`（同名ファイルがある場合は連番を付与）
- **デフォルト保存先（MediaStore）**:
  - 動画（`.mp4`）: `DCIM/100XREAL/`
  - メタデータ（`.json`）・ジャイロデータ（`.gcsv`）: `Documents/XREAL/100XREAL/`
- **任意フォルダ指定**: SAF（Storage Access Framework）で保存先ディレクトリを指定した場合、そのフォルダ配下に `.mp4` / `.json` / `.gcsv` をまとめて出力します（フォールバック時はアプリ固有の `Movies` 領域を使用）。

## ビルド方法

このプロジェクトは Gradle を使用せず、`scripts/build.ps1`（Windows）または `scripts/build.sh`（Linux / macOS）から Android SDK ツール（`aapt2`, `javac`, `d8`, `zipalign`, `apksigner`）を直接呼び出してビルドします。

### 必要な環境

- **JDK 21**（`build.ps1` は `jdk-21.0.12`、`build.sh` は `javac --release 8` でコンパイル）
- **Android SDK**: `build-tools/35.0.0` および `platforms/android-35/android.jar`

### ビルド手順

**Windows（PowerShell）**

```powershell
# 初回のみ Material Components (1.14.0) 等の依存ライブラリを取得する場合
python scripts/fetch_material_deps.py

# APK のビルド
.\scripts\build.ps1
```

**Linux / macOS**

```bash
export ANDROID_SDK_ROOT=/path/to/android-sdk
./scripts/build.sh
```

ビルドが成功すると `build/xreal-recorder.apk` が出力されます（`KEYSTORE` 未指定時はデバッグ用キーストアで署名されます）。

## プロジェクト構成

- `app/AndroidManifest.xml`: アプリ設定・権限定義（`com.syameimarukoa.xreal_recorder`）
- `app/src/com/syameimarukoa/xreal_recorder/`: アプリ本体（UI・`RecordService`・TCP/UVC/HID 通信・音声処理・Gyroflow Muxer 等）
  - `HeadlessReceiver.java`: `jp.xreal.recorder.DEV_CMD` インテントによる ADB 制御用レシーバー
- `app/src/main/jniLibs/arm64-v8a/libgyroflow_inject.so`: Gyroflow 公式 FFmpeg ベースの MP4 / `gyrf` 生成ネイティブライブラリ
- `scripts/`: ビルドスクリプト（`build.ps1`, `build.sh`）、依存取得・検証用スクリプト群
