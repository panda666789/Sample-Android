---
type: permanent
created: 2026-10-19
---

# 平板横屏采集与录像方向

## 行为约定

- 采集主页面在平板上跟随用户的屏幕方向设置；手机保留竖屏界面。
- 可用窗口至少 600 dp 宽且宽大于高时，左栏展示摄像头和录制操作，右栏展示可独立滚动的设备列表。竖屏恢复上下布局。
- 布局切换复用相机 SurfaceView，不重新创建 Activity 或整张布局，也不重置设备卡片和录制指示器的可见性。
- 实时预览按相机输出流的真实比例居中完整显示，空间不足时留边，不把人脸拉伸填满容器。平板竖屏预览最高 360 dp，并为设备列表预留空间。
- 录像方向在准备每段视频时确定，整段保持同一方向；需要改变成片横竖方向时，应停止当前录制后重新开始。
- 原有录像不自动改写。

## 方向计算

`DeviceOrientation` 在采集页面前台监听物理姿态。它使用实际打开摄像头的 `SENSOR_ORIENTATION` 和 `LENS_FACING`，不根据“手机/平板”或固定的前摄 270°、后摄 90°假设计算。

`VideoOrientation` 使用顺时针物理姿态：前摄 `(sensor - device + 360) % 360`，后摄 `(sensor + device) % 360`。屏幕旋转和物理姿态的符号不同；未收到传感器读数时，将 `Display.getRotation()` 转换后作为回退。设备平放导致姿态未知时保留最近一次有效值。

计算依据：[Android camera-samples OrientationLiveData](https://github.com/android/camera-samples/blob/master/CameraUtils/lib/src/main/java/com/example/android/camera/utils/OrientationLiveData.kt)。

普通录像、YUV 推理录像和 JPEG 推理录像都编码相机原始像素方向，并通过 MP4 方向标记表达播放方向。AI 图像单独旋转，避免像素已经旋转后再叠加完整方向标记。已有离线分析会读取 MP4 的旋转元数据。

## 实时预览比例

`CameraPreviewView` 从实际摄像头的 `getOutputSizes(SurfaceHolder.class)` 选择支持的预览尺寸，普通采集优先 1920×1080，AI 路径优先 640×480。在 UI 线程设置固定缓冲区，等待 Surface 实际尺寸匹配后再创建相机会话。

SurfaceView 已自动处理传感器与屏幕方向之间的旋转。本项目只使用 `PreviewGeometry` 计算等比居中缩放，保留布局给定的测量尺寸；竖屏的宽矮容器不能直接用于推断图像方向。旋转时按 View 当前的 Display 重新计算比例，缓冲区尺寸保持不变。主预览与画中画使用同一组件。

依据：[Android Camera2 SurfaceView 预览说明](https://developer.android.com/media/camera/camera2/camera-preview#surfaceview)。

## 2026-10-19 验证

- 单元测试 38 项通过，其中 5 项覆盖方向，5 项覆盖预览比例：前后摄四种姿态、不同传感器安装角度、量化边界、屏幕旋转回退、宽矮竖屏容器、4:3 推理/PiP、完整取景以及未测量视图。
- `:app:assembleDebug`、`:app:assembleDebugAndroidTest` 构建通过。
- 独立 Sentinel 静态复核未发现新增阻断问题。
- 小米平板 7（2410CRP4CC，Android 16）横屏真机验证：主界面使用完整窗口；五类设备卡片可见；竖屏切换恢复上下布局。检查后恢复原来的系统自动旋转设置。
- 同一设备前摄普通录像：传感器 270°、物理姿态 270°，写入方向 0°；实录约 5.798 秒，H.264 1920×1080，约 30 FPS。读取 MP4 并抽帧确认画面正向，无额外 90°旋转。
- 用户发现初版竖屏实时预览拉伸后，补充固定缓冲区与等比显示修复。修复版在同一平板竖放实录约 5.565 秒，实时画面为居中的 9:16，人物比例正常；MP4 保留竖屏所需方向标记。横屏实时预览同样确认比例正常。
- 录制中切换界面方向的连续性检查：完整视频 68.448 秒、2053 帧，可完整解码；相机服务只有开始/结束各一次连接事件，中间未重开。传感器帧元数据最大间隔约 66.649 ms，平均 33.341 ms。UIAutomator 因录制界面未进入 idle 状态超时，改用屏幕截图定位停止按钮完成测试；系统旋转设置已恢复。
- 新增 `CaptureLayoutInstrumentedTest` 可验证宽横屏、紧凑横屏和竖屏布局切换后的控件边界、预览比例及 View 身份；本次独立测试 APK 被设备以 `INSTALL_FAILED_USER_RESTRICTED` 取消安装，因此仅编译通过，未执行。布局另有上述真机界面验证。

尚未完成真机覆盖：反向横屏/倒置竖屏录像、手机回归、AI 两种录像模式和双摄。单元测试覆盖方向计算，但不替代这些相机/编码器端到端验证。
