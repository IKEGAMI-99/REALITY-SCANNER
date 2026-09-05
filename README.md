# REALITY SCANNER

Android向けの完全ローカルAIリアルタイム物体認識HUDです。

## APKダウンロード

**Snapdragon QNN / HTP対応 v0.1.4:**

[REALITY-SCANNER-v0.1.4-QNN-FIX.apk をダウンロード](https://github.com/IKEGAMI-99/REALITY-SCANNER/releases/download/v0.1.4/REALITY-SCANNER-v0.1.4-QNN-FIX.apk)

> 約299MB。YOLO26s / YOLO26nのQNNモデル、YOLO26n 640 XNNPACKフォールバック、YOLO26x互換モデルをAPK内に含みます。
>
> v0.1.1以降は固定debug署名を使用しているため、アプリ内UPDATEから上書き更新できます。

黒背景＋グリーンのCLI/タクティカルUIで、画面上部を正方形カメラ、下部を実処理ログが流れるライブターミナルとして構成しています。

## 現在の実装

- CameraXによるリアルタイムカメラ
- 正方形カメラ表示
- Android system bar / display cutoutを避けるSafe Insets対応
- Camera AnalysisとYOLO推論を別スレッドへ完全分離
- Qualcomm QNN / Hexagon HTPを最優先バックエンドとして使用
- HTP v79向けQNNコンテキストモデルを同梱
- QNN時は640x640入力
- QNN失敗時はYOLO26n 640 XNNPACKへ高速フォールバック
- 最終互換用としてYOLO26x 960 ONNXも同梱
- AI推論要求間隔40ms
- COCO 80クラス
- Track ID
- BBoxコーナーHUD
- BBox中央からの速度ベクトル
- 0.5秒先の予測位置表示
- 古いBBoxの外挿を0.6秒で停止
- 相対速度表示
- 低照度自動判定＋ヒステリシス
- 暗所時の露出補助＋AI入力デジタルゲイン
- 黒＋グリーンのCLI UI
- リアルタイム処理ログ
- JSONログ書き出し
- GitHub Releasesのアプリ内更新確認
- APKダウンロード＋インストーラ起動
- GitHub ActionsによるAndroidビルド

## v0.1.4 修正

POCO F7 Ultra上のv0.1.3では、QNN/HTP初期化に失敗して最終フォールバックのYOLO26x / CPU系実行へ落ち、約3.8秒/推論になっていました。

v0.1.4ではQNN context binary生成側とAndroid実行側のバージョンを揃えています。

```text
AOT export:
ONNX Runtime 1.26.0
onnxruntime-qnn 2.4.0
QAIRT compatibility 2.48.x

Android:
onnxruntime-android-qnn 1.26.0
qnn-runtime 2.48.0
```

起動時の優先順位:

```text
YOLO26s QNN / HTP v79
        ↓ load failed
YOLO26n QNN / HTP v79
        ↓ load failed
YOLO26n ONNX / XNNPACK 640
        ↓ load failed
YOLO26x ONNX / compatibility fallback
```

そのため、QNNが端末ファームウェア側の理由で利用できない場合でも、従来の約4秒/回のYOLO26xへ直行せず、まず640pxのYOLO26n XNNPACKを使います。

正常にNPUへ載った場合はターミナルに次のように表示されます。

```text
[QNN][INFO] YOLO26S-QNN loaded on HTP input=640x640
[MODEL][INFO] selected live detector YOLO26S-QNN/HTP
[YOLO][INFO] ... backend=YOLO26S-QNN/HTP
```

QNNが失敗して高速CPUフォールバックへ入った場合:

```text
[FAST][INFO] YOLO26N-XNN loaded input=640x640
[MODEL][INFO] selected live detector YOLO26N-XNN
[YOLO][INFO] ... backend=YOLO26N-XNN
```

## v0.1.3 修正

- Snapdragon 8 Elite向けQNN / Hexagon HTP経路を追加
- YOLO26s / YOLO26n QNNモデルを追加
- Safe Insetsで上部UI被りを修正

## v0.1.2 修正

- CameraXのImageAnalysisスレッドとYOLO推論スレッドを分離
- YOLO推論中もCamera AnalysisとHUD更新を継続
- YOLO結果は撮影元フレームのtimestampを保持
- Track再マッチング時に予測BBoxを利用
- XNNPACKフォールバックを追加

## v0.1.1 修正

POCO F7 Ultra上でYOLO26xをNNAPIへロードした際に、ONNX Runtimeが`AddNnapiSplit count [0] does not evenly divide dimension`で失敗する問題へ対応しました。

## AIモデル

フルAPKのGitHub Actionsでは以下を自動生成してAPKへ組み込みます。

```text
models/yolo26s_v79_qnn.onnx
models/yolo26n_v79_qnn.onnx
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

## レイアウト

```text
┌────────────────────────────┐
│ REALITY SCANNER // LOCAL   │
├────────────────────────────┤
│                            │
│        CAMERA 1:1          │
│     ┌ PERSON #0001 ┐       │
│     │       ●───────→ ○    │
│     └──────────────┘       │
│                            │
├────────────────────────────┤
│ >> PROCESS TERMINAL // LIVE│
│ [QNN] HTP session ready    │
│ [YOLO] objects=4           │
│ [VECTOR] #1 rel=0.32/s     │
│ > _                        │
│                            │
│ [PAUSE][CLEAR][EXPORT][UPD]│
└────────────────────────────┘
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
- KotlinはAGP 9のBuilt-in Kotlinを利用

Android Studioでリポジトリを開いてSyncするか、Gradle 9.6環境で:

```bash
gradle :app:assembleDebug
```

## アプリ内更新

GitHub Releasesの`latest`を確認し、Release Assets内の最初の`.apk`を更新APKとして扱います。

v0.1.1以降は固定debug署名を使用しています。

Androidの仕様上、初回は「不明なアプリのインストール」の許可が必要です。

## ログ

下部ターミナル表示とファイルログは同じ`AppLogger`を利用しています。

`[ EXPORT ]`からAndroidの保存UIを開き、JSONとして書き出せます。

## 今後

- ByteTrack / Optical Flowによる実フレーム追跡
- Depth推定
- 実距離とm/s速度
- ジャイロ＋Optical Flowによるカメラ移動補正
- YOLO26m/l/xのQNN実機検証
- Night専用YOLO
- Denoise / Low-Light Enhancement
- モデルマネージャー
- Thermal Management
