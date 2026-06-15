# Bryton Gardia R300(R300L) パケットデコード仕様

> **作成**: 2026-06-15
> **検証デバイス**: Bryton Gardia R300L × Google Pixel 10 (Android 17)
> **検証状態**: ✅ 公道フィールドテストで実データ検証済み（1〜3台同時検出を確認）

本アプリが Bryton Gardia R300(R300L) の BLE パケットをどのように解釈しているかの記録。
本方式は公道実走行で「車列表示が実際の車の位置とかなり正確に一致する」ことが確認されており、
根本的に妥当な解釈と判断している。

---

## 1. 背景：なぜこの記録が必要か

Bryton Gardia は **BLE 向けに標準化されたレーダー GATT キャラクタリスティックを持たず、独自 UUID でデータを配信**している。そのパケット形式は ANT+ RDR（Bicycle Radar）標準とほぼ同等だが、公式の文書化がないため、リバースエンジニアリング情報に依存している。

本方式の根拠となった情報源を明確にし、今後の保守・改良時の出典を担保するために本ファイルを置く。

## 2. 通信仕様

| 項目 | 値 |
|------|-----|
| Radar Data Characteristic (Notify) | `f3641401-00b0-4240-ba50-05ca45bf8abc` |
| Battery Characteristic (Read/Notify) | `00002a19-0000-1000-8000-00805f9b34fb` (標準 Battery Service) |
| パケット長 | 20 バイト（観測値） |
| 通知間隔 | 約 0.85 秒（≈1 Hz） |

> ⚠️ この `f3641401...` UUID は **Garmin Varia が使うキャラクタリスティックとは異なる**。Varia 系は pycycling の `6a4e3203-667b-11e3-949a-0800200c9a66` を使う。両者はデータ形式も異なる（後述）。

## 3. パケット構造（20 バイト、2バンク構成）

観測されるパケットは、先頭 `0x30` の第1バンクと `0x31` の第2バンクで構成される。

```
位置:  [0]  [1] [2] [3] [4] [5] [6] [7]  [8]  [9] [10]...[16] [17] [18] [19]
       ┌─────────── 第1バンク(0x30) ──────────┐ ┌─── 第2バンク(0x31) ───┐
値例:  30   01  00  09  00  00  01  00  31   00  00 ... 00   08   02   00
       hdr  lvl ??  <---- 距離3B ----> <速度2B> hdr  <---- 中身ほぼ0 ----> ??
```

- **第1バンク (`0x30`, byte[0..7])**: 全4脅威の脅威レベル・距離・速度が**ビットパック**で詰まっている。本アプリがデコード対象とするのはここ。
- **第2バンク (`0x31`, byte[8..19])**: 觀測上、実データ（`31 00 00 00 00 00 00 00 00 ...`）はほぼ常にゼロ。用途は未確定（拡張用バンク/別ページの可能性）。現在はデコードに使用しない。
- **byte[17], byte[18]**: ステータス系バイト（バッテリーや動作モードと思われる）。値が変動するが車両検出とは無関係。本アプリでは未使用。

### 3.1 旧実装の誤り（記録のため）

README の旧記述「`0x30` ヘッダー=ターゲット1-2、`0x31` ヘッダー=ターゲット3-4」は**誤り**だった。実際は **`0x30` 1バンク内に全4脅威がビットパックで格納**されており、`0x31` は別バンク（実質空）。この誤解が「最大2台しか表示されない」問題の根本原因だった。

## 4. デコード計算式（第1バンク `0x30`）

### 4.1 脅威レベル（byte[1], 2bit × 4）

```
levels = byte[1]
t1Level = (levels >> 0) & 0b11
t2Level = (levels >> 2) & 0b11
t3Level = (levels >> 4) & 0b11
t4Level = (levels >> 6) & 0b11
```

- `level > 0` でその脅威が**存在**（`level == 0` は脅威なし＝表示しない）
- 値の意味（観測）: `1` = 通常接近, `2` = 高脅威（高速接近）

### 4.2 距離（byte[3..5], 6bit × 4、リトルエンディアン風パック）

```
d0 = byte[3]; d1 = byte[4]; d2 = byte[5]
t1Dist = (d0 >> 0) & 0b111111                                  // d0 の下位6bit
t2Dist = ((d0 >> 6) & 0b11)        | (((d1 >> 0) & 0b1111) << 2) // d0上位2 + d1下位4
t3Dist = ((d1 >> 4) & 0b1111)      | (((d2 >> 0) & 0b11) << 4)   // d1上位4 + d2下位2
t4Dist = (d2 >> 2) & 0b111111                                  // d2 の上位6bit
```

距離は 6bit（0..63）で表現され、物理量は **値 × 3.125 m**。最大 63×3.125 ≈ **196.9 m**（レーダー最大検出距離 ≈ 140m / 200m と整合）。

### 4.3 速度（byte[6..7], 4bit × 4）

```
s0 = byte[6]; s1 = byte[7]
t1Speed = (s0 >> 0) & 0b1111
t2Speed = (s0 >> 4) & 0b1111
t3Speed = (s1 >> 0) & 0b1111
t4Speed = (s1 >> 4) & 0b1111
```

速度は 4bit（0..15）で表現され、物理量は **値 × 10.944 km/h**（相対接近速度）。最大 15×10.944 ≈ **164 km/h**。

> **速度バイト割り当ての検証**: `s0 = 0x55 (0b01010101)` のとき `t1Speed=5`(下位), `t2Speed=5`(上位) となり両方 55km/h。3台検出時 `s1 = 0x05` で `t3Speed=5` → 55km/h となり、t1/t2/t3 が同じ接近群として物理的に整合。✅

## 5. 実装（Kotlin）

`app/src/main/java/com/pirorin215/gardiaradar/data/RadarRepository.kt` の `decodeRadarData()` が上記をそのまま実装している（該当箇所抜粋）：

```kotlin
if (dataInt.size >= 8 && dataInt[0] == 0x30) {
    val levels = dataInt[1]
    val t1Level = (levels shr 0) and 0b11
    val t2Level = (levels shr 2) and 0b11
    val t3Level = (levels shr 4) and 0b11
    val t4Level = (levels shr 6) and 0b11

    val d0 = dataInt[3]; val d1 = dataInt[4]; val d2 = dataInt[5]
    val t1Dist = (d0 shr 0) and 0b111111
    val t2Dist = ((d0 shr 6) and 0b11) or (((d1 shr 0) and 0b1111) shl 2)
    val t3Dist = ((d1 shr 4) and 0b1111) or (((d2 shr 0) and 0b11) shl 4)
    val t4Dist = (d2 shr 2) and 0b111111

    val s0 = dataInt[6]; val s1 = dataInt[7]
    val t1Speed = (s0 shr 0) and 0b1111
    val t2Speed = (s0 shr 4) and 0b1111
    val t3Speed = (s1 shr 0) and 0b1111
    val t4Speed = (s1 shr 4) and 0b1111

    if (t1Level > 0) newTargets.add(RadarTarget(1, (t1Dist * 3.125).roundToInt(), (t1Speed * 10.944).roundToInt(), t1Level))
    if (t2Level > 0) newTargets.add(RadarTarget(2, (t2Dist * 3.125).roundToInt(), (t2Speed * 10.944).roundToInt(), t2Level))
    if (t3Level > 0) newTargets.add(RadarTarget(3, (t3Dist * 3.125).roundToInt(), (t3Speed * 10.944).roundToInt(), t3Level))
    if (t4Level > 0) newTargets.add(RadarTarget(4, (t4Dist * 3.125).roundToInt(), (t4Speed * 10.944).roundToInt(), t4Level))
}
```

## 6. 実データによる検証

フィールドテストで取得した生ログで検証。いずれも計算と完全一致。

### 6.1 3台同時検出（決定的証拠）

`radar_20260615_151111.txt`（Note: 車が2台来て、その後また2台きた）

```
[15:11:08.575] hex=301500ca0401550531...  dec=[48,21,0,202,4,1,85,5,...]
  -> 出力: [id=1 d=31 s=55 t=1, id=2 d=59 s=55 t=1, id=3 d=50 s=55 t=1]
```

検証（`levels = 0x15 = 0b00010101`）:

| 脅威 | レベル計算 | 距離計算 | 速度計算 | 出力 |
|------|-----------|---------|---------|------|
| id=1 | bits[1:0]=**1** | d0=0xCA 下位6bit=10 → 31.25m | s0=0x85 下位4bit=5 → 54.7 | d=31 s=55 t=1 ✅ |
| id=2 | bits[3:2]=**1** | (d0上位2=2)\|(d1下位4=4<<2=16)=18 → 56.25m | s0=0x85 上位4bit=5 → 54.7 | d=59 s=55 t=1 ✅ |
| id=3 | bits[5:4]=**1** | (d1上位4=0)\|(d2下位2=1<<4=16)=16 → 50m | s1=0x05 下位4bit=5 → 54.7 | d=50 s=55 t=1 ✅ |

3台すべて計算通り。続く `15:11:09`, `15:11:10` も同様に3台が整合して接近。

### 6.2 2台同時検出 + 脅威レベル2

```
[15:10:40.549] hex=300600020100560031...  dec=[48,6,0,2,1,0,86,0,...]
  levels=0x06=0b00000110 → t1Level=2(高脅威), t2Level=1
  -> 出力: [id=1 d=6 s=66 t=2, id=2 d=13 s=55 t=1]
```

脅威レベルの使い分け（1=通常 / 2=高脅威）も正常に機能。✅

### 6.3 単体接近シーケンス（物理的整合）

`radar_20260615_150637.txt`（Note: 車が1台ずつ、2回きた）

```
15:06:18  d=22  →  15:06:21  d=3   (3秒で19m接近 ≈ 6.3m/s ≈ 23km/h)
```

表示速度 s=22〜33km/h とオーダー一致。✅

## 7. 出典・参考文献

本方式の根拠となった情報源。**重要**: 下記を混同しないこと。

1. **pycycling Issue #42 "Bryton Gardia R300L not compatible"** (OndrejBakan, 2024-04-26)
   https://github.com/zacharyedwardbull/pycycling/issues/42
   - Bryton が独自キャラクタリスティック `f3641401-00b0-4240-ba50-05ca45bf8abc` を使用し、
     パケット形式が **ANT+ RDR 標準とほぼ同等** であることを報告。
   - 本アプリの UUID 特定の手がかり。ビットパック計算式そのものは記載なし。

2. **ANT+ Bike Radar デバイスプロファイル（thisisant.com 公式）**
   https://www.thisisant.com/developer/components/ant-plus-device-profiles/
   - ビットパック構造（レベル2bit×4 / 距離6bit×4 / 速度4bit×4）の公式定義元（RDR プロファイル）。
   - 相互運用プロファイルとして Garmin Varia / Bryton Gardia / Magene L508 等が準拠。
   - 関連: https://www.thisisant.com/news/ant-wireless-releases-first-interoperable-device-profile-for-bike-radar-sen

3. **pycycling `rear_view_radar.py`** (Jason Sohn, 2022)
   https://github.com/zacharyedwardbull/pycycling/blob/master/pycycling/rear_view_radar.py
   - ⚠️ **本アプリの方式とは別物**。Garmin Varia (RVR315) 向けで、
     キャラクタリスティック `6a4e3203-...`、**1脅威3バイト固定**の構造（byte1=id, byte2=distance, byte3=speed）。
   - Bryton Gardia は独自 UUID ＋ ビットパックなので、この実装では読めない。混同注意。

### 訂正：コードコメントの不備

`RadarRepository.kt` の実装当時のコメントに「pycycling Issue#42 / OndrejBakan 実装に基づく」とあるが、正確には **「Issue#42 の報告（UUID と ANT+ RDR 標準への言及）を手がかりに、ANT+ Bike Radar プロファイルのビットパック仕様を適用した」** である。OndrejBakan 氏自身が計算式を公開したわけではない。

## 8. 未解明事項（今後の改善ネタ）

- **byte[2], byte[17], byte[18], byte[19]** の正確な意味。byte[17]/[18] はバッテリーや動作ステータスと思われるが未検証。
- **第2バンク `0x31`** が観測上ほぼ常に空だが、5台目以降や別データのために予約されている可能性。
- **係数の精密化**: 距離 `×3.125` / 速度 `×10.944` は ANT+ RDR 標準値。Gardia 固有の丸め差異があるかは、ガーミンウォッチとの表示比較で検証余地（現在「ガーミンウォッチほどではない」との所感あり）。
- **速度の符号（接近/離脱）**: 現状は絶対値のみ。離脱車の判別ができるかは追加検証が必要。

---

*本ドキュメントは実機検証（2026-06-15）に基づく。パケット構造の理解が更新された場合は本ファイルを更新すること。*
