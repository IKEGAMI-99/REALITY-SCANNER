# REALITY SCANNER

Android向けの完全ローカルAIリアルタイム物体認識HUDです。

## APKダウンロード

**Snapdragon QNN / HTP対応 v0.1.3:**

[REALITY-SCANNER-v0.1.3-QNN-HYBRID.apk をダウンロード](https://github.com/IKEGAMI-99/REALITY-SCANNER/releases/download/v0.1.3/REALITY-SCANNER-v0.1.3-QNN-HYBRID.apk)

> 約290MB。YOLO26s/YOLO26nのQNNモデルとYOLO26x互換フォールバックモデルをAPK内に含みます。
>
> v0.1.1以降は固定debug署名を使用しているため、v0.1.2からv0.1.3はアプリ内UPDATEで上書き更新できます。

黒背景＋グリーンのCLI/タクティカルUIで、画面上部を正方形カメラ、下部を実処理ログが流れるライブターミナルとして構成しています。

## 現在の実装

- CameraXによるリアルタイムカメラ
- 正方形カメラ表示
- Android system bar / display cutoutを避けるSafe Insets対応
- Camera AnalysisとYOLO推論を別スレッドへ完全分離
- Qualcomm QNN / Hexagon HTPを最優先バックエンドとして使用
- YOLO26s QNN → YOLO26n QNN → YOLO26x ONNXの順に自動フォールバック
- HTP v79向けQNNコンテキストモデルを同梱
- QNN時は640x640入力、YOLO26x互換フォールバックは960x960入力
- AI推論要求間隔を40msまで短縮
- COCO 80クラス
- Track ID
- BBoxコーナーHUD
- BBox中央からの速度ベクトル
- 0.5秒先の予測位置表示
- YOLO更新間を速度ベクトルでBBox予測描画
- 古いBBoxの外挿を0.6秒で停止し、暴走するPRED表示を抑制
- 相対速度表示
- 低照度自動判定
- Low Light AUTOにヒステリシスを追加し、閾値付近のON/OFF連打を防止
- 暗所時の露出補助＋AI入力デジタルゲイン
- 黒＋グリーンのCLI UI
- リアルタイム処理ログ
- JSONログ書き出し
- GitHub Releasesのアプリ内更新確認
- APKダウンロード＋インストーラ起動
- GitHub ActionsによるAndroidビルド

## v0.1.3 修正

v0.1.2ではPOCO F7 Ultra上でカメラ/HUD側は25〜30fpsまで戻ったものの、YOLO26x本体はXNNPACKで約3.2〜3.9秒/回の推論時間が残っていました。

v0.1.3ではCPU最適化を続けるのではなく、Snapdragon 8 EliteのHexagon NPUを直接使うQNN経路を追加しています。

起動時の優先順位:

```text
YOLO26s QNN / HTP v79
        ↓ load failed
YOLO26n QNN / HTP v79
        ↓ load failed
YOLO26x ONNX / XNNPACK
```

ターミナルで正常にNPUへ載った場合は、例えば次のように表示されます。

```text
[QNN][INFO] YOLO26S-QNN loaded on HTP input=640x640
[MODEL][INFO] selected live detector YOLO26S-QNN/HTP
[YOLO][INFO] ... backend=YOLO26S-QNN/HTP
```

QNNが利用できない場合でも、従来のYOLO26x経路へ自動フォールバックします。

また、Android 15以降のedge-to-edge表示で上部ヘッダーが時計・カメラカットアウト・ステータスバーに潜り込む問題をSafe Insetsで修正しました。

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

QNNモデルは`format=qnn`, `name=79`, `imgsz=640`で生成します。

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
