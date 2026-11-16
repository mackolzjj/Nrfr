package com.github.nrfr.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.github.nrfr.OneTapStage
import com.github.nrfr.ui.theme.NrfrAdaptive

@Composable
fun OneTapScreen(
    stage: OneTapStage,
    detail: String,
    onOpenShizuku: () -> Unit,
    onRequestPermission: () -> Unit,
    onRetry: () -> Unit,
    onOpenMain: () -> Unit,
    onDone: () -> Unit
) {
    val busy = stage == OneTapStage.CHECKING || stage == OneTapStage.REAPPLYING

    val title = when (stage) {
        OneTapStage.CHECKING -> "正在检查"
        OneTapStage.NOT_INSTALLED -> "未安装 Shizuku"
        OneTapStage.WAITING_FOR_SHIZUKU -> "等待 Shizuku 启动"
        OneTapStage.NEED_PERMISSION -> "需要授权"
        OneTapStage.REAPPLYING -> "正在回写配置"
        OneTapStage.SUCCESS -> "回写完成"
        OneTapStage.NO_SAVED_CONFIG -> "还没有保存过配置"
        OneTapStage.FAILED -> "回写未完成"
    }

    val body = when (stage) {
        OneTapStage.CHECKING -> "正在检测 Shizuku 状态…"
        OneTapStage.NOT_INSTALLED -> "没有检测到 Shizuku。请先安装适配版 Shizuku，再回来点一键回写。"
        OneTapStage.WAITING_FOR_SHIZUKU ->
            "已为你打开 Shizuku。请在 Shizuku 里点一次「启动」，服务连上后这里会自动继续回写，无需再做任何操作。"
        OneTapStage.NEED_PERMISSION ->
            "Shizuku 已运行，需要授权本应用。若授权弹窗没有出现，点下方按钮重新请求。"
        OneTapStage.REAPPLYING -> "正在重新应用运营商配置，请稍候…"
        OneTapStage.SUCCESS ->
            "运营商配置已重新应用。现在可以打开 TikTok 验证了。"
        OneTapStage.NO_SAVED_CONFIG ->
            "Nrfr 还没有保存过配置。请先打开主程序保存一次，之后即可用一键回写。"
        OneTapStage.FAILED -> detail.ifBlank { "回写未完成，请重试。" }
    }

    val showProgress = busy
    val isSuccess = stage == OneTapStage.SUCCESS

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 28.dp, vertical = 40.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Spacer(Modifier.height(24.dp))

            Text(
                text = "一键回写",
                style = MaterialTheme.typography.headlineSmall,
                color = NrfrAdaptive.link()
            )

            Spacer(Modifier.height(28.dp))

            if (showProgress) {
                CircularProgressIndicator(
                    modifier = Modifier.size(52.dp),
                    color = NrfrAdaptive.link(),
                    strokeWidth = 4.dp
                )
                Spacer(Modifier.height(28.dp))
            }

            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                textAlign = TextAlign.Center,
                color = if (isSuccess) NrfrAdaptive.link() else MaterialTheme.colorScheme.onBackground
            )

            Spacer(Modifier.height(12.dp))

            Text(
                text = body,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(36.dp))

            when (stage) {
                OneTapStage.WAITING_FOR_SHIZUKU -> {
                    Button(
                        onClick = onOpenShizuku,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = NrfrAdaptive.link(),
                            contentColor = NrfrAdaptive.onLink()
                        ),
                        shape = RoundedCornerShape(14.dp)
                    ) { Text("重新打开 Shizuku") }
                    Spacer(Modifier.height(10.dp))
                    OutlinedButton(
                        onClick = onRetry,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp)
                    ) { Text("Shizuku 已启动，继续") }
                }

                OneTapStage.NEED_PERMISSION -> {
                    Button(
                        onClick = onRequestPermission,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = NrfrAdaptive.link(),
                            contentColor = NrfrAdaptive.onLink()
                        ),
                        shape = RoundedCornerShape(14.dp)
                    ) { Text("重新请求授权") }
                }

                OneTapStage.SUCCESS -> {
                    Button(
                        onClick = onDone,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = NrfrAdaptive.link(),
                            contentColor = NrfrAdaptive.onLink()
                        ),
                        shape = RoundedCornerShape(14.dp)
                    ) { Text("完成") }
                }

                OneTapStage.NO_SAVED_CONFIG -> {
                    Button(
                        onClick = onOpenMain,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = NrfrAdaptive.link(),
                            contentColor = NrfrAdaptive.onLink()
                        ),
                        shape = RoundedCornerShape(14.dp)
                    ) { Text("打开 Nrfr 主程序") }
                }

                OneTapStage.FAILED -> {
                    Button(
                        onClick = onRetry,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = NrfrAdaptive.link(),
                            contentColor = NrfrAdaptive.onLink()
                        ),
                        shape = RoundedCornerShape(14.dp)
                    ) { Text("重试") }
                }

                OneTapStage.NOT_INSTALLED -> {
                    Button(
                        onClick = onRetry,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = NrfrAdaptive.link(),
                            contentColor = NrfrAdaptive.onLink()
                        ),
                        shape = RoundedCornerShape(14.dp)
                    ) { Text("重新检测") }
                }

                OneTapStage.CHECKING, OneTapStage.REAPPLYING -> Unit
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}
