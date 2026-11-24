//! 数据同步的 UniFFI 投影：[`wind_mobile::sync::MobileSync`] 的薄包装。
//!
//! 同步逻辑（加密、后端、冲突、设备名册）全在主仓；这里只把 JSON 字符串进出的门面暴露给 Kotlin。
//! 状态目录必须与构造 [`crate::WindCore`] 的 `local_root` 同一个：核心的本地目录
//! 就是桌面语义里的 `%LOCALAPPDATA%/WindInput`，同步的设置、状态与机密放其下 `sync/`。

use std::path::Path;
use std::sync::Arc;

use wind_mobile::sync::MobileSync;

use crate::WindCore;

/// 同步操作失败，`reason` 是门面给的原文（中文，可直接展示）。
#[derive(Debug, uniffi::Error)]
pub enum SyncError {
    Failed { reason: String },
}

impl std::fmt::Display for SyncError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            SyncError::Failed { reason } => f.write_str(reason),
        }
    }
}

impl std::error::Error for SyncError {}

fn failed(reason: String) -> SyncError {
    SyncError::Failed { reason }
}

/// 数据同步句柄，与 [`WindCore`] 同生命周期。
#[derive(uniffi::Object)]
pub struct MobileSyncHandle {
    inner: Arc<MobileSync>,
}

#[uniffi::export]
impl MobileSyncHandle {
    /// - `user_dir`：用户目录（与构造核心的 `user_root` 同一个）
    /// - `state_dir`：本地状态目录（与构造核心的 `local_root` 同一个）
    #[uniffi::constructor]
    pub fn new(core: Arc<WindCore>, user_dir: String, state_dir: String) -> Arc<Self> {
        Arc::new(Self {
            inner: MobileSync::new(
                Path::new(&user_dir),
                Path::new(&state_dir),
                core.core.clone(),
            ),
        })
    }

    /// `{settings, installed, has_secret}`（JSON，不含机密）
    pub fn get_settings(&self) -> String {
        self.inner.get_settings()
    }

    /// 入参为同步设置 JSON；校验失败返回原因，不落盘
    pub fn set_settings(&self, json: String) -> Result<String, SyncError> {
        self.inner.set_settings(&json).map_err(failed)
    }

    /// 同步状态 JSON：`{enabled, phase, last_ok, last_error, pending, conflicts, …}`
    pub fn status(&self) -> String {
        self.inner.status()
    }

    /// 执行同步命令，阻塞至有回执（最多 60 秒），**必须在后台线程调用**。
    /// 回执可能含密钥（`export_key`），不要记日志。
    pub fn command(&self, op: String, args_json: String) -> Result<String, SyncError> {
        self.inner.command(&op, &args_json).map_err(failed)
    }

    /// 到期则后台起一轮同步并立即返回 true（键盘弹出时调用，耗时可忽略）
    pub fn sync_if_due(&self, min_interval_secs: u64) -> bool {
        self.inner.sync_if_due(min_interval_secs)
    }

    /// 停止同步线程，幂等
    pub fn shutdown(&self) {
        self.inner.shutdown();
    }
}
