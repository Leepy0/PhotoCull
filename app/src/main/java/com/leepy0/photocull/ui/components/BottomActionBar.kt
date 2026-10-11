package com.leepy0.photocull.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.leepy0.photocull.ui.theme.Spacing

/**
 * 화면 하단(엄지 영역) 주요 행동 영역. 제스처 바와 겹치지 않게 내비게이션 바 인셋을 처리한다.
 * [applyNavigationBarInset] = false: 하단 탭 바 위에 놓일 때(Scaffold가 인셋을 이미 처리함)
 */
@Composable
fun BottomActionBar(
    modifier: Modifier = Modifier,
    applyNavigationBarInset: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainer,
        tonalElevation = Spacing.zero,
    ) {
        Column(
            modifier = Modifier
                .then(if (applyNavigationBarInset) Modifier.navigationBarsPadding() else Modifier)
                .padding(horizontal = Spacing.m, vertical = Spacing.s),
            verticalArrangement = Arrangement.spacedBy(Spacing.xs),
            content = content,
        )
    }
}

/** 큰 주요 버튼(높이 56dp, 가로 꽉 참) */
@Composable
fun PrimaryActionButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    destructive: Boolean = false,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.fillMaxWidth().heightIn(min = Spacing.primaryButtonHeight),
        colors = if (destructive) {
            ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.error,
                contentColor = MaterialTheme.colorScheme.onError,
            )
        } else {
            ButtonDefaults.buttonColors()
        },
    ) {
        Text(text, style = MaterialTheme.typography.titleMedium)
    }
}
