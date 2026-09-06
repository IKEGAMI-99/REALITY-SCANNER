# REALITY SCANNER

Android向けの完全ローカルAIリアルタイム物体認識HUDです。

## APKダウンロード

**Snapdragon QNN / HTP対応 v0.1.9:**

[REALITY-SCANNER-v0.1.9-DEMO-AI.apk をダウンロード](https://github.com/IKEGAMI-99/REALITY-SCANNER/releases/download/v0.1.9/REALITY-SCANNER-v0.1.9-DEMO-AI.apk)

> 約299MB。YOLO26s / YOLO26n QNN external EPContext bundle、YOLO26n 640高速フォールバック、YOLO26x互換モデルをAPK内に含みます。
>
> v0.1.1以降は固定debug署名を使用しているため、アプリ内UPDATEから上書き更新できます。

黒背景＋グリーンのCLI / タクティカルHUDで、上部を正方形の映像領域、下部を実処理ログが流れるライブターミナルとして構成しています。

## v0.1.9 YouTubeデモAI推論

v0.1.8まではYouTubeデモは表示専用で、DEMOへ切り替えるとCameraX推論を停止したままBBoxを更新していませんでした。

v0.1.9ではYouTube表示中の正方形領域をAndroid `PixelCopy`で継続的に取得し、CameraXと同じDetectorへ投入します。

```text
YouTube / WebView
      ↓ PixelCopy latest frame
square demo frame
      ↓
DetectorCascade
      ↓
YOLO26s QNN / HTP
  or YOLO26n fallback
      ↓
TrackManager
      ↓
BBox / vector HUD
```

- DEMO映像に対して実際にYOLO推論を実行
- PixelCopyを100ms間隔で要求
- 推論中は新しいフレームをキューへ溜めずdropし、常に最新フレームへ追従
- CAMERA / DEMOごとにsource generationを持ち、切替前に処理中だったカメラ推論結果を破棄
- DEMO開始時に旧Trackをクリア
- DEMO中のHUD backendは`DEMO/<detector backend>`表示
- `[ CAMERA ]`でCameraXへ戻ってもDetectorは再ロードしない

現在のPOCO F7 Ultra実機でYOLO26n CPU fallbackが約175msの場合、DEMO推論は理論上およそ5fps前後で更新できます。QNN / HTPが有効になれば同じframe pumpのままさらに高頻度化できます。

## v0.1.8 修正

### YouTubeデモ再生

YouTube WebView埋め込みで「再生できません」になるケースへ対応しました。

- YouTube IFrame Player APIを使用
- 埋め込みページへ明示的なHTTPS originを設定
- `referrerpolicy=origin`を設定
- YouTube player error codeをAndroidターミナルへ通知
- error 153時は、明示的な`Referer`ヘッダ付きdirect embedへ自動フォールバック
- direct fallbackでも16:9映像を正方形HUDへcover表示

ターミナルでは主に以下を確認できます。

```text
YouTube player error 101 / 150 // video owner disabled embedded playback
YouTube player error 153 // retrying direct embed with explicit Referer
YouTube autoplay blocked // tap the player once to start playback
```

### BBoxの滑り修正

- HUD上の最大外挿時間を0.60秒から0.18秒へ短縮
- 相対速度`0.025/s`未満を静止扱いにしてBBoxを固定
- TrackManager側にもvelocity dead-zoneを追加
- 長い推論間隔から算出した速度の重みを下げる
- Track matching用の予測時間も5.0秒から1.2秒へ短縮

## YouTubeデモモード

下部ターミナルの`[ DEMO ]`ボタンから使用します。

1. `[ DEMO ]`を押す
2. YouTube URLまたは11文字のVideo IDを入力
3. `[ PLAY ]`でデモ再生＋YOLO推論開始
4. デモ中はボタンが`[ CAMERA ]`に変わり、押すとライブカメラへ復帰

対応URL例:

```text
https://www.youtube.com/watch?v=VIDEO_ID
https://youtu.be/VIDEO_ID
https://www.youtube.com/shorts/VIDEO_ID
https://www.youtube.com/live/VIDEO_ID
VIDEO_ID
```

通常の16:9 YouTubeプレイヤーを小さな横長表示にはせず、正方形HUD領域を完全に覆うように中央クロップして表示します。

```text
16:9 source
┌──────────────────────────────┐
│         crop left            │
│      ┌──────────────┐        │
│      │  visible 1:1 │        │
│      │   HUD area   │        │
│      └──────────────┘        │
│                  crop right  │
└──────────────────────────────┘
```

デモモード中はCameraXのbindだけを一時停止し、ロード済みDetectorと推論Executorは保持します。YouTubeの表示領域をPixelCopyして同じDetectorで推論します。

## 現在の実装

- CameraXリアルタイムカメラ
- 正方形カメラ表示
- YouTubeデモ再生
- YouTube動画フレームへのリアルタイムYOLO推論
- PixelCopy latest-frame demo pipeline
- YouTube 16:9映像の1:1中央クロップ / cover表示
- CAMERA ↔ YOUTUBE DEMO切替
- source generationによる切替前推論結果の破棄
- Android system bar / display cutout Safe Insets
- Camera AnalysisとYOLO推論の別スレッド化
- Qualcomm QNN / Hexagon HTP優先
- QNN external EPContext（wrapper ONNX + companion `.bin`）
- QNN 640x640入力
- external EPContextの小さなCPU helper nodeを許可
- QNN失敗時YOLO26n 640へフォールバック
- YOLO26n NCHW / NHWC自動判定
- YOLO26x 960最終互換モデル
- COCO 80クラス
- Track ID
- BBoxコーナーHUD
- BBox中央からの速度ベクトル
- 相対速度表示
- BBox予測外挿のdead-zone / short horizon
- 低照度自動判定＋ヒステリシス
- 暗所時露出補助＋AI入力デジタルゲイン
- リアルタイム処理ログ
- JSONログを`Downloads/REALITY_SCANNER`へ直接保存
- ログ保存後のread-backバイト検証
- GitHub Releasesアプリ内更新
- GitHub Actions自動APKビルド / Release

## 推論バックエンド優先順位

```text
YOLO26s QNN / HTP external context
        ↓
YOLO26n QNN / HTP external context
        ↓
YOLO26n ONNX / XNNPACK 640
        ↓
YOLO26n ONNX / CPU 640
        ↓
YOLO26x ONNX / compatibility fallback
```

正常にNPUへ載った場合:

```text
[QNN][INFO] YOLO26S-QNN EPContext loaded // HTP graph active // CPU helper nodes allowed
[MODEL][INFO] selected live detector YOLO26S-QNN/HTP
```

DEMO推論中は:

```text
[DEMO][INFO] frame inference started // PixelCopy latest-frame pump
[DEMO-YOLO][INFO] objects=... tracks=... infer=... backend=...
```

## AIモデル

フルAPKのGitHub Actionsでは以下を生成してAPKへ組み込みます。

```text
models/qnn_s/model.onnx
models/qnn_s/*.bin
models/qnn_n/model.onnx
models/qnn_n/*.bin
models/yolo26n.onnx
models/yolo26x.onnx
```

QNNモデル生成:

```bash
python scripts/export_yolo26_qnn.py
```

YOLO26x互換モデル生成:

```bash
python scripts/export_yolo26.py
```

## ログ書き出し

`[ EXPORT ]`を押すとAndroid 10以降では次へ直接JSONを保存します。

```text
Downloads/REALITY_SCANNER/reality_scanner_log_YYYYMMDD_HHMMSS.json
```

保存後に同じMediaStore URIを読み戻し、生成JSONと実ファイルのバイト数が一致した場合のみ成功扱いにします。

## ビルド環境

- Android Gradle Plugin 9.4.0
- Gradle 9.6
- compileSdk 37
- minSdk 28
- CameraX 1.6.2
- ONNX Runtime Android QNN 1.26.0
- Qualcomm QNN Runtime 2.48.0
- Java 17
- AGP 9 Built-in Kotlin

## 今後

- ByteTrack / Optical Flowによる実フレーム追跡
- Depth推定
- 実距離とm/s速度
- ジャイロ＋Optical Flowによるカメラ移動補正
- YOLO26m/l/x QNN実機検証
- Night専用YOLO
- Denoise / Low-Light Enhancement
- モデルマネージャー
- Thermal Management
