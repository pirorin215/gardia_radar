# GardiaRadar

車載BLEレーダー「Gardia」と連携して、後方から接近する車両を検知するAndroidアプリ。

Wear OS対応で、ウォッチでも強力な振動と音で警告します。

## 機能概要

- 🚗 Gardia BLEレーダーと接続して車両情報を受信
- 📱 スマホで接近車両を通知
- ⌚ Wear OSで強力な振動・音で警告
- 🔔 通知モードを3段階で調整可能

## 通知・振動・音の仕様

### スマホ（Phone）の通知

#### 通知スタイル
- 標準的な通知スタイル
  - タップ → アプリを開く
  - 「Dismiss」ボタン → 通知を消す
- `FLAG_INSISTENT` で繰り返し通知
- **フルスクリーン通知**（設定でON/OFF可能、デフォルトOFF）
  - ONのとき：ロック画面で全画面表示
  - OFFのとき：標準通知のみ

#### 振動パターン
```
[0, 1000ms, 200ms, 1000ms, 200ms, 1000ms]
 鳴る------   ----   ----   ----
```
- **FIRST_ONLY**: 最初の検知時に1回のみ振動
- **EVERY_TIME**: 新規車両検出時に1回ずつ振動

#### 音
- **なし**（通知音は鳴りません）

#### 通知内容

| モード | タイトル | テキスト | タイミング |
|--------|----------|----------|------------|
| FIRST_ONLY | Vehicle Detected! | Distance: Xm | 最初の1回のみ |
| EVERY_TIME | New Vehicle! | N vehicles approaching | 新規車両ごと |

---

### Wear OSの通知

#### 振動パターン
```
[0, 500ms, 200ms, 500ms, 200ms, 500ms, 200ms, 500ms]
 振幅255
```
- **最大振幅255**で強力な振動
- **FIRST_ONLY**: 最初の検知時に1回のみ振動（繰り返しなし）
- **EVERY_TIME**: 新規車両検出時に1回ずつ振動（繰り返しなし）

#### 音
- **アラーム音**（`TYPE_ALARM`）を**最大音量**でループ再生
- `USAGE_ALARM` カテゴリ

#### フルスクリーン警告画面
- **暗赤背景**（`#B71C1C`）
- 黄色い「⚠ VEHICLE!」テキスト
- ターゲット数と距離を表示
- DISMISSボタンで停止可能

---

## 通知モード

アプリ設定で**Phone**と**Wear OS**それぞれに以下の3モードから選択可能：

| モード | 動作 |
|--------|------|
| **OFF** | 通知なし |
| **FIRST_ONLY** | 最初の検知時のみ通知 |
| **EVERY_TIME** | 車両が増えるたびに通知 |

### 設定例

ユーザーはPhoneとWear OSで異なる通知モードを設定できます：

| Phone | Wear OS | 動作 |
|-------|---------|------|
| FIRST_ONLY | FIRST_ONLY | 両方とも最初のみ通知 |
| FIRST_ONLY | OFF | Phoneのみ通知、Wearは通知なし |
| OFF | FIRST_ONLY | Wearのみ通知、Phoneは通知なし |
| EVERY_TIME | FIRST_ONLY | Phoneは毎回、Wearは最初のみ |

### フルスクリーン通知設定

設定画面で「Fullscreen notification」スイッチをONにすると、ロック画面上で全画面通知が表示されます（デフォルトはOFF）。これはPhoneの通知にのみ適用されます。

---

## Phone ↔ Wear 連携

スマホとWear OSはWearable Message APIで通信：

| メッセージパス | 方向 | 動作 |
|----------------|------|------|
| `/radar-alert` | Phone → Wear | Wearで振動+音+画面起動 |
| `/radar-clear` | Phone → Wear | Wearのアラート停止 |

---

## アーキテクチャ

### Phoneモジュール
- **RadarScanService**: フォアグラウンドサービスでBLEスキャンを実行
- **RadarRepository**: BLE接続とGATT通信を管理
- **RadarNotificationManager**: CallStyle通知を生成
- **WearMessageSender**: Wear OSへメッセージ送信

### Wearモジュール
- **RadarListenerService**: WearableListenerServiceでPhoneからのメッセージを受信
- **RadarAlertActivity**: フルスクリーン警告画面を表示

---

## 開発環境

- Kotlin
- Jetpack Compose
- Koin（DI）
- Coroutines + Flow
- AndroidX Wear

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

Wear OSアプリのAPKをビルドします：

```bash
./gradlew :wear:assembleDebug
```

APKの場所：
```
wear/build/outputs/apk/debug/wear-debug.apk
```

### Wear OSデバイスで開発者モードを有効化

Wear OSデバイス上で：
1. 設定 → 关于 → バージョン情報
2. 「ビルド番号」を7回タップ
3. 設定に「開発者オプション」が表示されます
4. 開発者オプション → 「ADBデバッグ」をON

### PCとWear OSデバイスを接続

#### WiFi経由（推奨）

```bash
# Wear OSデバイスのIPアドレスを確認（設定 → 关于 → ネット情報）
adb connect 192.168.x.x:5555
```

#### USB経由

Wear OSデバイスをUSBドックで接続：
```bash
adb devices
```

### APKをインストール

```bash
adb install -r wear/build/outputs/apk/debug/wear-debug.apk
```

`-r` オプションは既存のアプリを置換します。

---

## ライセンス

MIT License
