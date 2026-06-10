package com.pirorin215.gardiaradar.ui.component

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Two-Stageスライダーコンポーネント
 *
 * 粗調整スライダーと微調整ボタン（+/-）を組み合わせた、操作性の高いスライダー。
 *
 * @param value 現在値
 * @param onValueChange 値変更時のコールバック
 * @param valueRange 値の範囲
 * @param coarseSteps 粗調整スライダーのステップ数（元のステップ数の1/4を推奨）
 * @param unit 値の単位（例: "秒"、"%"、"dBm"、"回"）
 * @param modifier Modifier
 */
@Composable
fun TwoStageSlider(
    value: Int,
    onValueChange: (Int) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    coarseSteps: Int,
    unit: String,
    modifier: Modifier = Modifier
) {
    // スライダーの現在値を状態として保持
    var coarseValue by remember { mutableStateOf(value.toFloat()) }

    // 外部からのvalue変更を同期（親コンポーネントから値が更新された場合）
    if (coarseValue.toInt() != value) {
        coarseValue = value.toFloat()
    }

    Column(modifier = modifier) {
        // 粗調整スライダー（ステップ数を削減して操作性向上）
        Slider(
            value = coarseValue,
            onValueChange = {
                coarseValue = it
                onValueChange(it.toInt())
            },
            valueRange = valueRange,
            steps = coarseSteps,
            modifier = Modifier.fillMaxWidth()
        )

        // 微調整UI（常時表示）
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(top = 8.dp)
        ) {
            // 減少ボタン
            IconButton(
                onClick = {
                    val newValue = (coarseValue - 1).coerceAtLeast(valueRange.start)
                    coarseValue = newValue
                    onValueChange(newValue.toInt())
                }
            ) {
                Icon(
                    imageVector = Icons.Default.Remove,
                    contentDescription = "減少"
                )
            }

            // 現在値表示
            Text(
                text = "${coarseValue.toInt()}$unit",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )

            // 増加ボタン
            IconButton(
                onClick = {
                    val newValue = (coarseValue + 1).coerceAtMost(valueRange.endInclusive)
                    coarseValue = newValue
                    onValueChange(newValue.toInt())
                }
            ) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = "増加"
                )
            }
        }
    }
}
