# GardiaRadar

車載BLEレーダー「Gardia R300L」と連携して、後方から接近する車両を検知するAndroidアプリ。

Wear OS対応で、ウォッチでも強力な振動と音で警告します。

## 機能概要

- 🚗 Gardia R300L BLEレーダーと接続して車両情報を受信
- 🔋 レーダー電池残量をBLE経由で取得・表示（低電池通知対応）
- 📱 スマホで接近車両をリアルタイム表示（Garmin風レーンビュー）
- ⌚ Wear OSで強力な振動・音で警告
- 🔔 通知モードを3段階で調整可能
- 🎨 接続状態をカラーで視覚化（緑=接続/赤=切断）

## スマホアプリ

### メイン画面

- **ステータスバー**: 接続状態（緑/赤）+ 「再接続」ボタン + レーダー電池残量
- **ターゲットリスト**: 検出中の車両一覧（距離・速度・脅威度）
- **レーンビュー**: 右側にGarmin風の縦型レーダー表示
  - 自転車アイコン（上端）
  - 縦線 + 50/100/150/200mのメモリ
  - 車両アイコンを距離に応じた縦位置に配置
  - 脅威度で色分け（赤=高速接近、白=通常）

### 設定画面

| 設定 | 説明 | デフォルト |
|------|------|-----------|
| Phone Notifications | スマホ通知モード（OFF/FIRST_ONLY/EVERY_TIME） | FIRST_ONLY |
| Wear OS Notifications | WearOS通知モード（同上） | FIRST_ONLY |
| Fullscreen notification | ロック画面で全画面通知 | OFF |
| Clear suppression time | 車列クリア後の通知抑制時間（0-60秒） | 10秒 |
| **Radar Low Battery Threshold** | **レーダー低電池しきい値（0-100%）** | **20%** |
| Theme Mode | テーマ（SYSTEM/LIGHT/DARK） | SYSTEM |

---

## Wear OSアプリ

### メイン画面

```
        🟩🟩🟩🟩🟩        ← 接続状態バー（緑/赤/グレー）
           🚴             ← 自転車アイコン
            |             ← 縦線
           --             ← 50m メモリ
            |
       ⚫ 50m              ← 車両アイコン + 距離
            |
           --             ← 100m メモリ
            |
            |
           --             ← 150m メモリ
            |
            |
           --             ← 200m メモリ
            |
14:32      ⌚ 85%          ← 時刻/曜日 ＋ ウォッチ電池
  火       📡 42%          ← レーダー電池
```

### 接続・切断時のフィードバック

WearOSアプリは接続状態変化時に以下を実行：

| 状態 | 振動 | 音 | アプリ前面表示 |
|------|------|----|----------------|
| 接続 | 短く2回 | ビープ1回 | ✅ （WakeLockで画面起動） |
| 切断 | 長めに1回 | ビープ2回 | ✅ → **10秒後に自動終了** |

### 電池最適化

- 時計更新: **1分間隔**（分の切り替わりに同期）
- 電池取得: **イベント駆動**（ポーリングなし）
- データ更新: **1秒スロットリング**で過剰更新を抑制
- 画面消灯: OS設定に従う

---

## 通知・振動・音の仕様

### スマホ（Phone）の通知

#### 通知スタイル
- 標準的な通知スタイル
  - タップ → アプリを開く
  - 「Dismiss」ボタン → 通知を消す
- **フルスクリーン通知**（設定でON/OFF可能、デフォルトOFF）
  - ONのとき：ロック画面で全画面表示

#### 振動パターン
```
[0, 1000ms, 200ms, 1000ms, 200ms, 1000ms]
```

#### 音
- **なし**（通知音は鳴りません）

#### 低電池通知
レーダー電池がしきい値を下回ると別チャンネルで通知：
- タイトル: 「レーダー電池残量低下」
- 本文: 「Gardia R300L: XX% (しきい値: YY%)」
- 重複防止: 1回のみ通知、充電で回復したら自動キャンセル

---

### Wear OSの通知（アラート）

#### 振動パターン
```
[0, 500ms, 200ms, 500ms, 200ms, 500ms, 200ms, 500ms]
 振幅255（最大）
```

#### 音
- **アラーム音**（`TYPE_ALARM`）を最大音量でループ再生
- **30秒タイムアウト**: clear信号が来ない場合も自動停止

---

## 通知モード

Phone/Wear OSそれぞれで3モードから選択可能：

| モード | 動作 |
|--------|------|
| **OFF** | 通知なし |
| **FIRST_ONLY** | 最初の検知時のみ通知 |
| **EVERY_TIME** | 車両検知中は1秒ごとに通知 |

---

## Phone ↔ Wear 通信

### Wearable Message API（アラート用）

| メッセージパス | 方向 | 動作 |
|----------------|------|------|
| `/radar-alert` | Phone → Wear | Wearで振動+音+画面起動 |
| `/radar-clear` | Phone → Wear | Wearのアラート停止 |

### Wearable Data API（状態同期用）

| データパス | 方向 | 内容 |
|-----------|------|------|
| `/radar-targets` | Phone → Wear | 車両数と距離配列（最大4台） |
| `/radar-connection-state` | Phone → Wear | 接続状態（Boolean） |
| `/radar-battery` | Phone → Wear | レーダー電池残量（0-100%） |

---

## アーキテクチャ

### Phoneモジュール
- **RadarScanService**: フォアグラウンドサービスでBLEスキャンを実行
- **RadarRepository**: BLE接続・GATT通信・電池取得を管理
- **RadarNotificationManager**: 車両通知 + 低電池通知を生成
- **WearableDataHost**: Wear OSへデータアイテム送信
- **WearMessageSender**: Wear OSへメッセージ送信

### Wearモジュール
- **MainActivity**: ウォッチ画面（時刻・曜日・電池・接続状態・車列表示）
- **WearableDataListener**: Phoneからのデータアイテムを受信・ブロードキャスト
- **RadarListenerService**: Phoneからのメッセージ（アラート）を受信

---

## BLE通信仕様

### 対象デバイス
- デバイス名に `Gardia` または `R300L` を含む

### GATT特性

| 用途 | UUID | 方式 |
|------|------|------|
| レーダーデータ | `f3641401-00b0-4240-ba50-05ca45bf8abc` | Notification |
| 電池残量 | `00002a19-0000-1000-8000-00805f9b34fb` | Read（標準Battery Service） |

### レーダーデータパケット構造

- **Byte 0 = 0x30 ヘッダー**: 最大2ターゲット
- **Byte 8 = 0x31 ヘッダー**: 追加2ターゲット（計4台まで）

---

## 開発環境

- Kotlin
- Jetpack Compose
- Koin（DI）
- Coroutines + Flow
- AndroidX Wear
- Wearable Data Layer API

---

## ビルド

```bash
# Phoneアプリ
./gradlew :app:assembleDebug

# Wear OSアプリ
./gradlew :wear:assembleDebug
```

---

## Wear OSアプリのインストール

### ビルド

```bash
./gradlew :wear:assembleDebug
```

APKの場所：
```
wear/build/outputs/apk/debug/wear-debug.apk
```

### Wear OSデバイスで開発者モードを有効化

1. 設定 → システム → バージョン情報
2. 「ビルド番号」を7回タップ
3. 開発者オプション → 「ADBデバッグ」をON

### PCとWear OSデバイスを接続

#### WiFi経由（推奨）

```bash
# Wear OSデバイスのIPアドレスを確認（設定 → システム → バージョン情報）
adb connect 192.168.x.x:5555
```

#### USB経由

```bash
adb devices
```

### APKをインストール

```bash
adb install -r wear/build/outputs/apk/debug/wear-debug.apk
```

---

## ライセンス

MIT License
