# REALITY SCANNER

Android向けの完全ローカルAIリアルタイム物体認識HUDです。

## APKダウンロード

**v0.2.1 FAST20:**

[REALITY-SCANNER-v0.2.1-FAST20.apk をダウンロード](https://github.com/IKEGAMI-99/REALITY-SCANNER/releases/download/v0.2.1/REALITY-SCANNER-v0.2.1-FAST20.apk)

> 約341MB。YOLO26s / YOLO26n QNN external EPContext、YOLO26n 320 FAST20、YOLO26n / YOLO26s 640 float ONNX、YOLO26x互換モデルを含みます。
>
> v0.1.1以降は固定debug署名を使用しているため、アプリ内UPDATEから上書き更新できます。

黒背景＋グリーンのCLI / タクティカルHUDで、上部を正方形の映像領域、下部を実処理ログが流れるライブターミナルとして構成しています。

## v0.2.1 FAST20

POCO F7 Ultraのv0.2.0実機ではQNN GPU上のYOLO26n 640が約126〜131ms/推論、DEMO AI表示は約4.8fpsでした。640のままでは20fpsに必要な50ms未満へ届かないため、速度優先の320x320モデルを追加しました。

- YOLO26n FAST20入力: 320x320
- 640比で画像ピクセル計算量は約1/4
- QNN GPUを優先、利用不可なら320モデルをCPU実行
- confidence threshold 0.38
- YouTube PixelCopy間隔: 100ms → 40ms
- DEMO推論ゲート: 80ms → 40ms
- Camera推論ゲート: 40ms
- 推論中のフレームはqueueせずdropし、常に最新フレームを優先
- v0.2.0 Tracking 2.0を継続使用

POCO F7 Ultra + QNN GPUではおおむね15〜20 AI FPSを目標にしています。実測値は温度、動画内容、AndroidのGPUスケジューリングで変動します。

正常なFAST20 GPU fallback表示例:

```text
BACKEND YOLO26N-FAST20-QNN-GPU
INFER 30-60 ms
AI 15-20 FPS
```

NPU / HTPが利用できる場合は、より高精度な640 QNNモデルを先に使用します。

## v0.2.0 Tracking 2.0

- クラス完全一致だけでなく意味グループを使った追跡マッチング
- vehicle / animal / furniture / container / electronics 内のクラス揺れを許容
- IoU＋中心距離＋ラベル関係を使うgreedy one-to-one association
- αβ motion filterによる中心位置・速度予測
- ラベル履歴の投票で一瞬のクラス誤認識を抑制
- 原則2回以上検出されるまでBBoxを表示しないfalse-positive suppression
- 高confidence検出のみ初回から即表示
- stale Track TTLを0.9秒へ短縮
- Detectorの実測推論時間に応じてHUD予測時間を自動調整
- 静止物体のdead-zoneを維持しながら動体だけ前方補正

## 推論バックエンド優先順位

```text
YOLO26s QNN external context / Hexagon HTP (NPU)
        ↓
YOLO26n QNN external context / Hexagon HTP (NPU)
        ↓
YOLO26n 320 FAST20 / QNN GPU
        ↓
YOLO26n 320 FAST20 / CPU
        ↓
YOLO26n 640 / QNN GPU or CPU
        ↓
YOLO26x compatibility fallback
```

## YouTube DEMO AI

下部の`[ DEMO ]`からYouTube URLまたはVideo IDを入力すると、16:9動画を正方形HUD領域へ中央crop / cover表示します。

YouTube表示領域はAndroid `PixelCopy`で継続取得し、CameraXと同じDetector / Trackerへ投入します。

```text
YouTube / WebView
      ↓ PixelCopy latest frame
square demo frame
      ↓
DetectorCascade
      ↓
YOLO
      ↓
Tracking 2.0
      ↓
BBox / vector HUD
```

- FAST20ではPixelCopyを40ms間隔で要求
- 推論中は古いフレームをqueueせずdrop
- CAMERA / DEMO source generationで切替前の推論結果を破棄
- YouTube error 153時は明示Referer付きdirect embedへfallback
- `[ CAMERA ]`で戻ってもDetectorは保持

## 現在の実装

- CameraXリアルタイムカメラ
- 正方形カメラ表示
- YouTubeデモ再生＋動画フレームへのYOLO推論
- Qualcomm QNN / Hexagon HTP優先
- QNN GPU float-model fallback
- QNN external EPContext（wrapper ONNX + companion `.bin`）
- YOLO26n 320 FAST20
- YOLO26s / YOLO26n 640
- YOLO26x 960最終互換モデル
- COCO 80クラス
- Tracking 2.0
- Track ID / BBox / confidence
- BBox中央からの速度ベクトル
- 推論遅延連動HUD予測
- 低照度自動判定＋ヒステリシス
- 暗所時露出補助＋AI入力デジタルゲイン
- リアルタイム処理ログ
- JSONログを`Downloads/REALITY_SCANNER`へ保存＋read-back検証
- GitHub Releasesアプリ内更新
- GitHub Actions自動APKビルド / Release

## AIモデル

```text
models/qnn_s/model.onnx
models/qnn_s/*.bin
models/qnn_n/model.onnx
models/qnn_n/*.bin
models/yolo26n_320.onnx
models/yolo26s.onnx
models/yolo26n.onnx
models/yolo26x.onnx
```

## ログ書き出し

`[ EXPORT ]`を押すとAndroid 10以降では次へ直接JSONを保存します。

```text
Downloads/REALITY_SCANNER/reality_scanner_log_YYYYMMDD_HHMMSS.json
```

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

- Optical FlowによるDetector間フレーム追跡
- ByteTrack相当のhigh/low confidence二段association
- Depth推定
- 実距離とm/s速度
- ジャイロ＋Optical Flowによるカメラ移動補正
- Night専用YOLO / denoise / low-light enhancement
- Thermal Management
