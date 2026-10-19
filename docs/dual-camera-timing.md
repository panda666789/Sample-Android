---
type: permanent
created: 2026-10-19
---

# 双摄视频帧与采集时间的对应

## 适用路径与使用

Android 13/API 33 及以上、关闭实时推理、选择“双摄”时，使用 `TimedDualRecorder`。单摄、实时推理和较低版本 Android 仍使用原有录像路径，不生成本报告，也不声称具有同样的时间映射验证。

设备必须报告前后摄并发支持，两颗相机时间源均为 `SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME`，且支持当前输出组合。任一条件不满足时明确失败，不静默退回不可校验的双摄路径。录像仍通过 Surface 输入的 AVC 硬件编码器完成，无逐帧 CPU 图像转换；视频本身不包含音轨，音频仍由现有独立采集模块保存。

录制时先打开全部相机，再配置会话。双方配置完成、预览各预热 15 帧后提交录像请求。停止时等待双方 capture sequence 和 session idle，再结束编码。准备过程中停止不会产生可用于推理的片段。

录制结束后在主页面显示核对结果。只有原始文件、逐帧映射和导出条件均通过时，才生成：

```text
Session_.../
  front/video_timestamps.csv
  back/video_timestamps.csv
  sync_frame_pairs.csv
  dual_sync_report.json
  inference/dual_sync/
    face.mp4
    finger.mp4
    clip_video_timestamps_face.csv
    clip_video_timestamps_finger.csv
    pair_manifest.json
```

前后原始录像和原有 `frame_metadata_*.csv` 仍保留。派生短片在系统媒体库中扫描登记，便于用安贞 APP 的文件选择器选取。应选择同一 `inference/dual_sync` 下的两段短片，并填写结果弹窗或 manifest 给出的真实毫秒起点，不能混用原录像的起点。

`face`/`finger` 是前后摄的用途命名，程序不验证拍摄对象、同一人、手指照明或模型输入质量。技术导出成功不等于模型或临床验证通过。

## 时间与内容证据

1. Camera2 编码输出显式使用 `TIMESTAMP_BASE_SENSOR`，API 34+关闭 readout timestamp，记录曝光起点。
2. 读取编码输出的原始 `codecPtsUs`，按传感器纳秒值除以 1000、最多 1 μs 量化误差进行一对一匹配。缺失、多候选或重复值都失败，不猜固定偏移。
3. 每段文件以首个实际写入的关键帧归零；编码数据成功写入后，才登记样本及规范化 AVC NAL SHA-256。
4. 文件关闭后，用 MediaExtractor 反读全部样本，核对数量、逐序内容摘要、关键帧及播放时间严格递增。Annex B 与 AVCC 的封装差异被规范化，但所有 NAL 的内容及顺序保留。
5. 保存传感器时间、编码时间、请求写入的相对时间及文件实际时间。`video_pts_us` 是请求值的兼容别名；应使用 `actual_file_pts_us` 查询最终 MP4 时间。

Android `MediaMuxer` 可能量化/平滑接近的帧间隔，因此“文件 PTS 与请求 PTS 必须相差小于 100 μs”不是可靠的样本身份条件。初轮真机前摄出现 7059 μs 累计差；按 AOSP `MPEG4Writer` 90 kHz 时基与 stts 合并算法可逐包复现全部 8051 个输出样本。最终实现以内容身份确认对应关系，实际时间偏移作为诊断保留，不扩大阈值伪装通过。

报告 schema_version=2。`verified` 仅表示两路编码样本的传感器映射与文件身份全部核对；不表示同时曝光、临床有效或硬件同步。逐行 `sample_identity_status` 只描述 payload 摘要，文件整体还有 count/key/PTS 检查。

参考：[Camera2 时间基准](https://developer.android.com/reference/android/hardware/camera2/params/OutputConfiguration#TIMESTAMP_BASE_SENSOR)、[并发打开次序](https://developer.android.com/reference/android/hardware/camera2/CameraManager#getConcurrentCameraIds())、[停止后等待 onReady](https://developer.android.com/reference/android/hardware/camera2/CameraCaptureSession.StateCallback#onReady(android.hardware.camera2.CameraCaptureSession))、[AOSP MPEG4Writer](https://android.googlesource.com/platform/frameworks/av/+/refs/heads/main/media/libstagefright/MPEG4Writer.cpp)。

## 推理片段导出

原始映射和文件均验证通过后，选择能独立解码的关键帧对，要求真实起点差不超过 100 ms、共同覆盖至少 18 秒。每路最多 30 秒、1800 帧，候选片段和实际派生文件都检查 25–35 FPS；实际文件严格使用安贞引擎的边界比较。

导出仅重新封装，不修改原始帧、不插帧、不把两路起点伪写为相同值。每一路派生视频再做完整样本身份复核，逐帧 CSV 保留源样本索引、采集时间和实际播放时间。全部成功后才将临时目录发布为 `dual_sync`；失败保留原始录像及原因，不发布成功 manifest。

100 ms 来自当前安贞离线程序的输入拒绝门槛，不是经模型方确认的生理信号或临床精度标准。这个独立安贞 APP 仍按标称帧率估算索引，不读取新增逐帧 CSV；要让它严格使用每帧时间，还需要修改其独立项目，本次未改该项目。

## 验证记录（2026-10-19）

设备：小米平板 7，2410CRP4CC，Android 16，前后摄 REALTIME。

| 项目 | 原实现已有录制 | 新实现第二轮 | 最终修补版录制 |
|---|---:|---:|---:|
| 比较基础 | 仅 capture CSV | 实际 MP4 帧完整映射 | 实际 MP4 帧完整映射 |
| 首记录/首帧绝对差 | 92.882 ms（日志首行） | 17.977 ms | 8.600 ms |
| 每路实际视频样本 | 4105 / 4121 | 1039 / 1039 | 6098 / 6098 |
| 视频样本映射+身份通过率 | 未建立保证 | 100% / 100% | 100% / 100% |
| 最近时间配对中位差 | 4.519 ms | 13.038 ms | 8.775 ms |
| 最近时间配对 P95 | 11.843 ms | 15.116 ms | 15.960 ms |
| 最近时间配对最大差 | 12.750 ms | 15.343 ms | 17.054 ms |

旧实现与新实现的统计基础不同，不能把这张表解释为曝光误差全面变小；首帧启动差和可追溯性得到改善，独立传感器相位仍会变化。没有使用共同光学事件验证硬件曝光同步。

- 最终录制共同区间约 203.148 秒，两路 capture 回调数、编码样本数、文件样本数均为 6098；没有未匹配或摘要不一致样本。
- 派生两片各 900 帧，时长约 29.990 / 29.984 秒，约 30 FPS；文件与原始样本摘要一致，电脑 FFmpeg 完整解码无错误。
- 最终版提前停止测试：准备过程中约 1 秒停止，两摄正常关闭，报告 `verified=false/export_ready=false`，不发布推理文件；随后再次打开双摄并完成停止，资源未被前次取消占用。
- 全部单元测试 96 项通过，含回调乱序、重复/歧义、不同时间域、局部低帧率、精确 FPS 边界、NAL 填充零、样本替换/重排和合法封装时间偏移；`:app:assembleDebug` 通过。
- `:app:lintDebug` 实际运行完成分析，但被仓库已有的 41 个错误阻断（首项为 `BLEService` 权限检查）；本次新增同步目录及其 Activity 集成的 API/权限问题已清零，没有添加 lint baseline 或关闭检查。
- 独立 Sentinel 指出的 session 排空、导出局部帧率、Annex B padding、实际 FPS 精确边界均已修复并复核关闭。

未覆盖：其它厂商/Android 13–15 设备、长时间热降频、多设备外设满负载、共同光学事件交叉验证，以及安贞模型端到端分数比对。单摄与 AI 原有路径不因本次自动获得同步保证。
