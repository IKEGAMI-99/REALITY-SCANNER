# REALITY SCANNER

Android向けの完全ローカルAIリアルタイム物体認識HUDです。

## APKダウンロード

**v0.2.0 TRACK / NPU / GPU:**

[REALITY-SCANNER-v0.2.0-TRACK-NPU-GPU.apk をダウンロード](https://github.com/IKEGAMI-99/REALITY-SCANNER/releases/download/v0.2.0/REALITY-SCANNER-v0.2.0-TRACK-NPU-GPU.apk)

> 約332MB。YOLO26s / YOLO26n QNN external EPContext、YOLO26n 640 float ONNX、YOLO26s 640 float ONNX、YOLO26x互換モデルを含みます。
>
> v0.1.1以降は固定debug署名を使用しているため、アプリ内UPDATEから上書き更新できます。

黒背景＋グリーンのCLI / タクティカルHUDで、上部を正方形の映像領域、下部を実処理ログが流れるライブターミナルとして構成しています。

## v0.2.0

### Tracking 2.0

v0.1.xの軽量Trackerは同一ラベルだけを同一物体として扱っていたため、`car → bus → car`のような一時的なクラス揺れでTrack IDが頻繁に作り直されていました。また古いTrackを6秒保持していたため、DEMOのような動きの速い映像ではBBoxが映像へ追いつかないように見える原因になっていました。

v0.2.0では以下へ変更しています。

- クラス完全一致だけでなく意味グループを使った追跡マッチング
- vehicle / animal / furniture / container / electronics 内のクラス揺れを許容
- IoU＋中心距離＋ラベル関係を使うgreedy one-to-one association
- αβ motion filterによる中心位置・速度予測
- ラベル履歴の投票で一瞬のクラス誤認識を抑制
- 原則2回以上検出されるまでBBoxを表示しないfalse-positive suppression
- 高confidence検出のみ初回から即表示
- stale Track TTLを6.0秒から0.9秒へ短縮
- Detectorの実測推論時間に応じてHUD予測時間を自動調整
- 静止物体のdead-zoneを維持しながら動体だけ前方補正

### NPU / GPU

推論優先順位は次の構成です。

```text
YOLO26s QNN external context / Hexagon HTP (NPU)
        ↓
YOLO26n QNN external context / Hexagon HTP (NPU)
        ↓
YOLO26n float ONNX / QNN GPU
        ↓
YOLO26n / XNNPACK
        ↓
YOLO26n / CPU
        ↓
YOLO26x compatibility fallback
```

v0.1.9のPOCO F7 UltraではQNN EPContextが`QNNExecutionProvider` source名の互換性チェックで拒否され、XNNPACKもQNN版ORTでは利用できなかったため、実際には`YOLO26N-XNN-CPU`で動作していました。

v0.2.0のQNN生成工程ではEPContextのsource属性を現行ORT QNNが受理する`QnnExecutionProvider`へ正規化し、生成後にCIで再検証します。実機側でHTPがまだ拒否された場合は、float ONNXをQNN GPU backendへロードしてからCPUへフォールバックします。

正常にNPUへ載った場合:

```text
BACKEND YOLO26S-QNN/HTP
```

GPU fallbackが成功した場合:

```text
BACKEND YOLO26N-QNN-GPU
```

CPUの場合:

```text
BACKEND YOLO26N-CPU
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

- PixelCopyを100ms間隔で要求
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

フルAPKのGitHub Actionsでは以下を生成・同梱します。

```text
models/qnn_s/model.onnx
models/qnn_s/*.bin
models/qnn_n/model.onnx
models/qnn_n/*.bin
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
