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
- 1秒振動 → 0.2秒休止 → 繰り返し

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
 振幅255で繰り返し
```
- **最大振幅255**で強力な振動
- 0.5秒振動 → 0.2秒休止 → 繰り返し

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

アプリ設定で以下の3モードから選択可能：

| モード | 動作 |
|--------|------|
| **OFF** | 通知なし |
| **FIRST_ONLY** | 最初の検知時のみ通知 |
| **EVERY_TIME** | 車両が増えるたびに通知 |

### フルスクリーン通知設定

設定画面で「Fullscreen notification」スイッチをONにすると、ロック画面上で全画面通知が表示されます（デフォルトはOFF）。

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

## ライセンス

MIT License
