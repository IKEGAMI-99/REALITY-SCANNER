# REALITY SCANNER

Android向けの完全ローカルAIリアルタイム物体認識HUDです。

## APKダウンロード

**YOLO26x内蔵フル版 v0.1.1:**

[REALITY-SCANNER-v0.1.1-FULL-YOLO26x.apk をダウンロード](https://github.com/IKEGAMI-99/REALITY-SCANNER/releases/download/v0.1.1/REALITY-SCANNER-v0.1.1-FULL-YOLO26x.apk)

> 約300MB。YOLO26xモデルをAPK内に含むため、別途モデルファイルを用意する必要はありません。
>
> v0.1.0は一時的なdebug署名で作成されていたため、v0.1.1への移行時だけ一度v0.1.0をアンインストールしてからインストールしてください。v0.1.1以降は固定署名を使用します。

黒背景＋グリーンのCLI/タクティカルUIで、画面上部を正方形カメラ、下部を実処理ログが流れるライブターミナルとして構成しています。

## 現在の実装

- CameraXによるリアルタイムカメラ
- 正方形カメラ表示
- 30fps以上のプレビューをAI推論から分離
- ONNX RuntimeベースのYOLO検出エンジン
- NNAPI優先、NNAPIがモデルを拒否した場合はCPUへ自動フォールバック
- COCO 80クラス
- Track ID
- BBoxコーナーHUD
- BBox中央からの速度ベクトル
- 0.5秒先の予測位置表示
- 相対速度表示
- 低照度自動判定
- 暗所時の露出補助＋AI入力デジタルゲイン
- 黒＋グリーンのCLI UI
- リアルタイム処理ログ
- JSONログ書き出し
- GitHub Releasesのアプリ内更新確認
- APKダウンロード＋インストーラ起動
- GitHub ActionsによるAndroidビルド

## v0.1.1 修正

POCO F7 Ultra上でYOLO26xをNNAPIへロードした際に、ONNX Runtimeが`AddNnapiSplit count [0] does not evenly divide dimension`で失敗する問題へ対応しました。

NNAPIセッション作成に失敗した場合、モデルロード全体を失敗扱いにせず、CPU Execution Providerでセッションを自動再生成します。ターミナルには`NNAPI FAILED -> CPU FALLBACK`と表示されます。

## AIモデル

通常のソースツリーには巨大なモデルバイナリを含めていません。

YOLO26xをONNXへexportし、以下のどちらかへ配置します。

```text
app/src/main/assets/models/yolo26x.onnx
```

またはアプリのprivate storage:

```text
files/models/yolo26x.onnx
```

エクスポート例:

```bash
pip install ultralytics
python scripts/export_yolo26.py
```

生成された`yolo26x.onnx`を`app/src/main/assets/models/`へコピーしてください。

モデルが存在しない場合でもカメラ・HUD・ログ・アップデートUIは起動し、ターミナルに`MODEL MISSING`が表示されます。

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
│ [CAMERA] preview started   │
│ [YOLO] objects=4           │
│ [VECTOR] #1 rel=0.32/s     │
│ [LOWLIGHT] AUTO -> ACTIVE  │
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
- ONNX Runtime Android 1.28.0
- Java 17
- KotlinはAGP 9のBuilt-in Kotlinを利用

Android Studioでリポジトリを開いてSyncするか、Gradle 9.6環境で:

```bash
gradle :app:assembleDebug
```

## アプリ内更新

GitHub Releasesの`latest`を確認し、Release Assets内の最初の`.apk`を更新APKとして扱います。

v0.1.1以降は固定debug署名を使用するため、以降のビルドは同じ署名を維持できます。

Androidの仕様上、初回は「不明なアプリのインストール」の許可が必要です。勝手にインストールすることはありません。

## ログ

下部ターミナル表示とファイルログは同じ`AppLogger`を利用しています。

`[ EXPORT ]`からAndroidの保存UIを開き、JSONとして書き出せます。

## 今後

- ByteTrack本実装への置換
- Depth推定
- 実距離とm/s速度
- ジャイロ＋Optical Flowによるカメラ移動補正
- QNN/HTP最適化
- Night専用YOLO
- Denoise / Low-Light Enhancement
- モデルマネージャー
- Thermal Management
