---
type: transitional
created: 2026-06-03
expires_when: Android 患者信息与模板对齐功能完成、验证通过并经用户确认验收后
related_to: /Users/panda/FacialCollectionIOS
---

# Android/iOS Patient Template Alignment Plan

## Goal

改造 Android 项目，使模板管理、模板新建/编辑、患者信息收集、患者记录展示与编辑能力对齐参考 iOS 项目 `/Users/panda/FacialCollectionIOS`。

用户明确要求：

- 必须完全对齐 iOS 在模板编辑、患者信息收集、展示、编辑等方面的能力。
- Android 内置模板只提供 iOS 中带 ICD-10 的“心脑血管通用”模板。
- 不能对已有正常功能产生负面影响，尤其是视频采集、采集数据记录、血氧仪对接等。
- 大任务期间本文件作为 live plan，后续进度、证据、决策和新发现及时更新。

## Current Evidence

### iOS Reference

- `FacialCollection/Models/FormTemplate.swift`
  - Defines `FieldType`, `FieldCondition`, `FieldDefinition`, `FormSection`, `DepartmentTemplate`.
- `FacialCollection/Models/BuiltInTemplates.swift`
  - Defines three built-in templates.
  - Android should only include `cardio_cerebrovascular`.
- `FacialCollection/Models/PatientInfoData.swift`
  - Dynamic patient record payload with `templateId`, `templateName`, `patientId`, `sessionId`, `recordingStartTime`, `submitTime`, `fields`, `photoFileName`.
  - Text formatting groups filled values by template section.
- `FacialCollection/Storage/TemplateManager.swift`
  - Manages built-in + custom templates, active template, duplicate, blank create, save/delete custom templates.
- `FacialCollection/Views/DynamicPatientInfoView.swift`
  - Dynamic form rendering.
  - Supports text, number, decimal, picker, segmented, multi-select, date, text area, blood pressure, ICD-10 single/list, photo.
  - Saves patient records as JSON in current session `PatientInfo/`.
- `FacialCollection/Views/TemplateListView.swift`
  - Template list, active selection, preview, duplicate built-in, create custom, edit/delete custom.
- `FacialCollection/Views/TemplateEditorView.swift`
  - Edit template name/description, sections, fields, options, required flag, units, placeholders, conditional display.
- `FacialCollection/Views/PatientRecordsView.swift`
  - Scans all experiments/sessions for patient JSON records.
  - Supports search by patient id/name, viewing rows, editing and saving records.
- `FacialCollection/Models/ICD10Diagnosis.swift`
  - Loads `icd10_nhsa_common.json`, searches code/name, stores diagnosis as `"code name"`.
- `FacialCollection/Resources/icd10_nhsa_common.json`
  - ICD-10 catalog, 3.56 MB.

### Android Current State

- Git root is `/Users/panda/AndroidStudioProjects/Sample/main`.
- Current patient form is fixed-field:
  - `app/src/main/java/com/tsinghua/sample/activity/PatientInfoActivity.java`
  - `app/src/main/res/layout/activity_patient_info.xml`
- Current patient info output:
  - Saves text files under session `info/` as `subject_info_<timestamp>.txt`.
- Current session path:
  - `Movies/FacialCollection/{experimentId}/Session_{wallMs}/`.
  - Subdirs include `front`, `back`, `imu`, `audio`, `ring`, `ecg`, `spo2`, `inference`, `markers`, `info`.
- Current recording completion path:
  - `ListActivity.navigateToPatientInfo()` starts `PatientInfoActivity` after stop/post-process.
- Protected existing paths:
  - `ListActivity` recording lifecycle, `RecordingCoordinator`, camera processors/helpers, `OximeterManager`, `OximeterService`, ECG/ring/IMU/audio recorders.

## Research Evidence

Triggered because patient information contains PII and Android currently writes under public external storage.

- Tavily search found OWASP Android Data Storage and Android official storage/security docs.
- WebSearch independently found Android official sensitive external storage, Android data storage overview, Android security checklist, and OWASP Android storage results.
- Tavily extract from Android official sensitive external storage page:
  - Sensitive data should always be stored on internal storage where possible.
  - If sensitive data must be stored on external storage, encrypt it; Android Keystore is recommended for key storage.
- Tavily extract from Android data storage overview:
  - Internal storage is always available and reliable for app-dependent data.
  - Android 11+ scoped storage narrows external access, but storage choice still depends on use case.
- Tavily extract from OWASP MASTG Android Data Storage:
  - Apps should identify sensitive data and classify it; PII and private information need protection.

Sources to cite in final engineering decision:

- Android Developers: `https://developer.android.com/privacy-and-security/risks/sensitive-data-external-storage`
- Android Developers: `https://developer.android.com/training/data-storage/`
- Android Developers: `https://developer.android.com/privacy-and-security/security-tips`
- OWASP MASTG: `https://mas.owasp.org/MASTG/0x05d-Testing-Data-Storage`

## Decision Needed

Storage/security route affects exact Android/iOS alignment and existing export behavior.

| Option | Description | Pros | Cons |
|---|---|---|---|
| A | Keep session-visible external path and write unencrypted JSON in `PatientInfo/`, matching iOS structure most closely | Least invasive; easiest for researchers to inspect/export; minimal impact on existing recording dirs | Leaves PII in external/public storage; conflicts with Android official best practice for sensitive data |
| B | Keep session-visible external path, but encrypt patient JSON files; write readable text only if user explicitly chooses later | Keeps current session packaging while reducing PII exposure | More code and test surface; record browser/editor must decrypt; needs encryption/key handling |
| C | Store patient JSON internally, and optionally write non-sensitive pointer/manifest into session dir | Best aligns with Android security guidance | Breaks simple session-folder export symmetry; less aligned with iOS current path; larger UX implications |

Recommended route to discuss: B if patient records must travel with session folders; C if security/compliance wins over folder-level export convenience; A only if this is offline research and user explicitly accepts PII-in-folder risk.

## Decisions

- 2026-06-03: User clarified patient information is stored only on hospital-use phones.
- 2026-06-03: Adopt a controlled Option A for this Android retrofit: keep patient records in the session-visible `PatientInfo/` directory as readable JSON/text-compatible data, matching iOS/export workflow and avoiding encryption/internal-storage migration in this iteration.
- 2026-06-03: PII risk acceptance context: user stated patient information is only stored on hospital-use phones; this is the basis for accepting readable session-folder JSON in this iteration.
- 2026-06-03: Preserve Android session directory names instead of renaming `Session_<timestamp>` after patient record edits, to avoid disrupting existing video, SpO2, IMU, ECG, ring, audio, and post-processing paths. Patient JSON filenames may still be based on patient name like iOS.
- 2026-06-03: After Sentinel review, pause before technical risk fixes so the user can decide which findings should be fixed immediately, backlogged, or accepted.
- 2026-06-03: User clarified blood pressure should not be required in any department template. Android therefore prevents `BLOOD_PRESSURE` fields from being saved as required, instead of adding support for required blood pressure validation.
- 2026-06-03: User requested patient record deletion to delete the corresponding whole `Session_...` folder, including patient info, videos, and device data, so researchers do not need to remove folders manually.

## Planned Implementation Tasks

1. Confirm route and exact acceptance criteria with user.
2. Add tests first for pure model/storage behavior:
   - built-in template contains only `cardio_cerebrovascular`;
   - required field validation;
   - field serialization mirrors iOS shape;
   - ICD-10 search/parsing behavior;
   - patient text formatting/blood pressure/list diagnosis behavior;
   - template persistence CRUD for custom templates.
3. Add Android model/storage layer:
   - `FieldType`, `FieldCondition`, `FieldDefinition`, `FormSection`, `DepartmentTemplate`.
   - `BuiltInTemplates` with only the ICD-10 template.
   - `FieldValue`, `PatientInfoData`, formatter.
   - `ICD10Catalog`.
   - `TemplateManager` / repository.
   - patient record repository.
4. Add assets:
   - copy iOS ICD-10 JSON into Android assets or raw resource.
5. Replace fixed `PatientInfoActivity` with dynamic template form:
   - session info;
   - all field types needed for the built-in template and custom templates;
   - required validation;
   - conditional display;
   - save flow to selected storage route.
6. Add template list/editor/preview screens:
   - built-in section;
   - custom section;
   - set active template;
   - duplicate built-in;
   - create blank template;
   - edit/delete custom template;
   - edit sections and fields.
7. Add patient records list/editor:
   - scan all experiments/sessions;
   - search by patient id/name;
   - open/edit/save record;
   - preserve iOS-compatible JSON semantics.
8. Wire navigation with minimal recording-path impact:
   - add entry points from settings or main list for templates and records.
   - keep `ListActivity.navigateToPatientInfo()` call shape stable where possible.
9. Regression verification:
   - local unit tests;
   - Gradle compile/test;
   - inspect changed files for accidental edits to protected recording paths;
   - if possible, app launch/screen smoke via emulator or in-app browser equivalent is not applicable here.
10. Sentinel review at completion because this touches user path, data/PII, API-like JSON contract, and multiple business files.

## Progress

- [x] Confirmed global/project instructions; no project-specific `AGENTS.md` exists under Sample.
- [x] Identified git root as `/Users/panda/AndroidStudioProjects/Sample/main`.
- [x] Compared iOS model/template/record/editor files against current Android fixed patient form.
- [x] Added template new/edit requirement after user reminder.
- [x] Completed required Tavily + WebSearch dual-source research for PII/external storage risk.
- [x] User decision on storage/security route.
- [x] Tests written.
- [x] RED attempted: Gradle first lacked system Java, then ran with Android Studio JBR and failed because Android SDK/local.properties is missing on this machine.
- [x] Android model/storage implementation first pass.
- [x] UI implementation first pass for dynamic patient form, template list/editor, and patient records list/editor.
- [x] Regression verification with unit tests, debug build, and XML syntax checks after SDK install.
- [x] Sentinel review completed.
- [x] User decision on Sentinel technical risk fixes.
- [x] Sentinel technical risk fixes, excluding accepted PII plaintext storage risk.
- [x] Additional user-requested delete behavior: deleting a patient record now removes the whole corresponding `Session_...` directory.
- [x] Real-device install/start smoke after latest delete/session safety and photo/time fixes.
- [x] Patient info time semantics: new legacy `info/subject_info_*.txt` files label recording and submit times as `UTC+8`, while old UTC text and new UTC+8 text both display as correct Beijing time in patient records.

## Verification Notes

- 2026-06-03: `./gradlew testDebugUnitTest --tests com.tsinghua.sample.patient.PatientTemplateModelTest` failed because no Java runtime was discoverable.
- 2026-06-03: Retried with `JAVA_HOME=/Applications/Android Studio.app/Contents/jbr/Contents/Home`; Gradle ran but failed with `SDK location not found`.
- 2026-06-03: Environment blockers are two-step: no system Java Runtime; Android Studio JBR works, but Android SDK path is missing.
- 2026-06-03: Searched common SDK paths (`~/Library/Android`, `~/Android`, `/Library/Android`) and did not find an Android SDK. Need Android SDK path or Android Studio project sync before final compile/test verification.
- 2026-06-03: Implemented controlled Option A: session-visible `PatientInfo/*.json`, Android session directory names preserved.
- 2026-06-03: Added Android-only patient/template model/storage package under `com.tsinghua.sample.patient`.
- 2026-06-03: Added dynamic patient form, template management pages, patient records page, Manifest entries, and main screen entry buttons.
- 2026-06-03: `xmllint --noout` over layout XML files passed.
- 2026-06-03: Re-ran Gradle test with Android Studio JBR; still blocked only by missing Android SDK path.
- 2026-06-03: `~/Desktop/program-dev-common-pitfalls.md` is missing in this environment, so Sentinel could not read that checklist.
- 2026-06-03: User approved continuing Sentinel technical fixes and asked Codex to download an Android SDK.
- 2026-06-03: Installed Android SDK under `~/Library/Android/sdk`, accepted SDK licenses, installed `platform-tools`, `platforms;android-34`, and `build-tools;34.0.0`, then wrote gitignored `local.properties`.
- 2026-06-03: `sdkmanager` requires Java; system Java is missing, but Android Studio JBR at `/Applications/Android Studio.app/Contents/jbr/Contents/Home` works for SDK management and should be used for Gradle verification.
- 2026-06-03: SDK blocker is cleared for this repo; next verification commands should set `JAVA_HOME=/Applications/Android Studio.app/Contents/jbr/Contents/Home`.
- 2026-06-03: Baseline unit test now passes after SDK install: `JAVA_HOME=/Applications/Android Studio.app/Contents/jbr/Contents/Home ./gradlew testDebugUnitTest --tests com.tsinghua.sample.patient.PatientTemplateModelTest`.
- 2026-06-03: Added and passed Sentinel regression tests for custom-template `patientId` protection, unique same-name patient JSON files, atomic save/rename failure preservation, same-name rename conflict handling, and iOS-compatible date input/storage helpers.
- 2026-06-03: Full Android verification passed: `JAVA_HOME=/Applications/Android Studio.app/Contents/jbr/Contents/Home ./gradlew testDebugUnitTest assembleDebug`.
- 2026-06-03: Layout XML syntax check passed again: `find app/src/main/res/layout -name '*.xml' -print0 | xargs -0 xmllint --noout`.
- 2026-06-03: iOS JSON compatibility verified with `swift docs/verification/verify_ios_patient_json.swift`; output: `iOS patient JSON decode OK`.
- 2026-06-03: Debug APK produced at `app/build/outputs/apk/debug/app-debug.apk`.
- 2026-06-03: Final Sentinel was attempted twice but the subagent channel stalled: first agent ran for more than 12 minutes without status before shutdown; second narrowed agent also returned no status after more than 5 minutes and was shut down. Independent final blind review is therefore blocked by tooling, not by Gradle/build.
- 2026-06-03: User asked to retry full Sentinel and wait 30 minutes. Third full Sentinel completed successfully.
- 2026-06-03: Added deletion behavior requested by user: `PatientRecordRepository.deleteRecord()` recursively deletes the record's `Session_...` directory, so PatientInfo, videos, SpO2, ECG, IMU, ring, audio, inference, markers and legacy `info` files are removed together.
- 2026-06-03: Added safety guard so delete only proceeds for existing directories whose names start with `Session_`.
- 2026-06-03: Preserved recording-path stability: no changes were made to `RecordingCoordinator`, camera helpers/processors, Oximeter, ECG, IMU, ring, or audio recorder implementations in this delete/session fix.
- 2026-06-03: Fixed patient JSON `recordingStartTime` semantics: Android now stores an ISO-like timestamp derived from `Session_<wallMs>` while the UI continues to show the human recording duration as `录制时长`.
- 2026-06-03: Made legacy `info/subject_info_*.txt` writing best-effort after JSON save, so export compatibility failures do not report the main patient JSON as failed.
- 2026-06-03: Fixed photo persistence edges: draft photos are per field, hidden-field drafts are cleaned, finalization checks/copies instead of ignoring rename failure, and editing existing records preserves photo field values.
- 2026-06-03: Legacy fixed-form scan now accepts Chinese full-width colon labels such as `姓名：赵六` and `病历号：MR004`.
- 2026-06-03: Focused regression passed after latest fixes: `JAVA_HOME=/Applications/Android Studio.app/Contents/jbr/Contents/Home ./gradlew testDebugUnitTest --tests com.tsinghua.sample.patient.PatientTemplateModelTest`.
- 2026-06-03: Full Android verification passed after latest fixes: `JAVA_HOME=/Applications/Android Studio.app/Contents/jbr/Contents/Home ./gradlew testDebugUnitTest assembleDebug`.
- 2026-06-03: Layout XML syntax check passed after latest fixes: `find app/src/main/res/layout -name '*.xml' -print0 | xargs -0 xmllint --noout`.
- 2026-06-03: Installed debug APK to wireless device `192.168.31.78:40169` with `adb install -r app/build/outputs/apk/debug/app-debug.apk`; install returned `Success`.
- 2026-06-03: Started `com.tsinghua.sample/.activity.ListActivity` on device; `dumpsys activity activities` showed `ResumedActivity: .../.activity.ListActivity`, `pidof com.tsinghua.sample` returned process `9069`, and recent logcat had no `FATAL EXCEPTION`.
- 2026-06-03: Tightened whole-session delete with canonical root containment: when `PatientRecordRepository` has a scan root, it refuses to delete a `Session_...` directory outside that root. Added regression `patientRecordRepositoryRefusesToDeleteSessionOutsideScanRoot`.
- 2026-06-03: Re-ran focused patient tests, full unit/build verification, XML check, reinstall and launch after containment guard. Device `192.168.31.78:40169` returned install `Success`; `am start -W` returned `Status: ok`; `dumpsys` showed `ResumedActivity: .../.activity.ListActivity`; `pidof` returned process `13925`; recent logcat had no `FATAL EXCEPTION`.
- 2026-06-03: Final Sentinel found three high-risk items: legacy text record edit disappearing, recursive delete symlink/path escape, and too-broad `Session_` delete guard. All three were fixed immediately.
- 2026-06-03: Legacy `info/subject_info*.txt` edits now save into `PatientInfo/*.json` instead of writing JSON into the legacy `info` folder, so rescanning continues to find the edited record.
- 2026-06-03: Whole-session delete now accepts only `Session_<digits>` names, refuses roots outside the configured FacialCollection root, and refuses session directories containing symlinks to avoid deleting outside the target session tree.
- 2026-06-03: Added focused regressions for malformed session names (`Session_backup`), session directories outside the scan root, symlink escape protection, and legacy edit migration to `PatientInfo/*.json`.
- 2026-06-03: Addressed lower-risk Sentinel items that were cheap and local: legacy record timestamps now parse `Session_<wallMs>` into ISO-like strings, custom template writes use temp-file atomic move, duplicate custom field IDs are renamed during sanitization, and delete refusal UI says the directory was not deleted because it is missing or outside the capture root.
- 2026-06-03: Remaining Sentinel notes accepted/backlogged: patient data remains plaintext per user decision; Activity-level automation and real recording-file preview/output comparison are not fully automated in this pass and should be considered for a later instrumentation/manual QA round.
- 2026-06-03: Final verification after Sentinel fixes passed: `JAVA_HOME=/Applications/Android Studio.app/Contents/jbr/Contents/Home ./gradlew testDebugUnitTest --tests com.tsinghua.sample.patient.PatientTemplateModelTest`, `JAVA_HOME=/Applications/Android Studio.app/Contents/jbr/Contents/Home ./gradlew testDebugUnitTest assembleDebug`, and layout `xmllint`.
- 2026-06-03: Final device smoke after Sentinel fixes passed on `192.168.31.78:40169`: install returned `Success`, `am start -W` returned `Status: ok`, `pidof com.tsinghua.sample` returned process `15875`, and recent logcat showed no `FATAL EXCEPTION`.
- 2026-06-26: Updated future legacy `info/subject_info_*.txt` output to write `录制开始时间(UTC+8)` and `信息提交时间(UTC+8)`, without adding duplicate raw UTC lines. Existing JSON keeps UTC ISO storage for machine compatibility.
- 2026-06-26: Legacy record scanning now understands both old `录制开始时间: 2026-...Z` UTC text and new `录制开始时间(UTC+8): yyyy-MM-dd HH:mm:ss` text, normalizing both to internal UTC ISO before patient-record display converts back to UTC+8.
- 2026-06-26: Patient record list/editor labels recording time as `UTC+8` and uses fixed UTC+8 conversion instead of depending on the phone's default timezone.
- 2026-06-26: Time semantics verification passed: `JAVA_HOME=/Applications/Android Studio.app/Contents/jbr/Contents/Home ./gradlew testDebugUnitTest assembleDebug` and `git diff --check`.

## Sentinel Report

Raw Sentinel output:

```text
## 阶段 1：相关类判定
- 主要：A 表单路由 + B JSON契约
- 主要：C 文件一致性 + D PII
- 主要：F 资源 + H 边界
- 横切：L 敏感数据 + T 测试 + P 兼容
- 未涉及：资金、DB迁移、第三方API

## 阶段 2：技术风险（Punch List）
[高][B] iOS Date 字段被 Android 存成 String
[高][B] 患者 JSON 未做 iOS 解码真测
[高][L] PII 明文写公共 Movies 目录
[高][C] 改名先删旧文件，写失败即丢记录
[中][C] 同名患者会覆盖同名 JSON
[中][A] 编辑记录不校验必填项
[中][A] 记录编辑把日期当文本输入
[中][A] 记录编辑丢失 PHOTO 字段编辑能力
[中][H] 自定义模板可删光 patientId
[中][H] patientId 缺失会触发空指针
[中][F] ICD10 全量载入主线程解析
[中][P] Android 13+ 公共目录权限未验
[中][T] Gradle 未跑通，编译风险未关
[中][T] 当前机器先缺 Java Runtime
[低][P] 未验证横竖屏状态保留
[低][T] 未覆盖 Activity 运行路径

## 阶段 3：工作方式元问题
[高] 指定 pitfalls 路径不可读
[高] SDK 阻塞外还有 Java 阻塞未记录
[中] iOS 语义对齐缺 golden fixture
[中] PII 路线需补风险接受记录
[中] 大改动未见设备端冒烟证据
[中] 未见采集全链路回归证据
[低] live plan expires_when 非中文可判定条件
```

Triage status:

- Meta fixes applied immediately: frontmatter `expires_when` localized; Java + SDK blocker recorded; missing pitfalls file recorded; PII acceptance context recorded.
- Technical fixes are approved except the accepted PII plaintext storage risk, per user clarification that patient information is only stored on hospital-use phones.
- 2026-06-03: Current status after resume: raw Sentinel report is already in this plan; implementation resumed with user approval for remaining technical risk fixes.
- 2026-06-03: Fixed storage/template/date items: `PatientRecordRepository` now writes via temp file + atomic move where supported, avoids same-name overwrites, returns actual saved file, and deletes old renamed files only after successful write; `TemplateRepository` now enforces required `patientId`; `PatientValueFormatter` centralizes date display/storage conversion.
- 2026-06-03: Fixed Activity-level Sentinel items: patient record edit validates required fields before closing the dialog, date fields display as `yyyy-MM-dd` and save as date values, photo fields are shown read-only and preserved like iOS, `patientId` cannot be deleted from template editor, ICD-10 asset parsing moved off the main thread, and PatientInfo save/photo save now checks `PatientInfo` directory writability with clearer permission errors.
- 2026-06-03: Existing data-capture implementation files remain untouched; `ListActivity` change is limited to new navigation buttons for patient records and templates.
- 2026-06-03: Final independent Sentinel review is blocked because two spawned agents stalled without returning a report. Local replacement checks completed: unit tests, debug build, XML syntax, iOS JSON decode fixture, changed-file review, and protected capture-path diff inspection.
- 2026-06-03: Third full Sentinel report returned and identified one high issue, three medium issues, one low issue, plus residual device/manual test gaps. Awaiting user decision on which findings to fix immediately.
- 2026-06-03: User approved fixing the final Sentinel findings.
- 2026-06-03: Final Sentinel findings fixed:
  - date edits now only accept `yyyy-MM-dd` or ISO instant values and otherwise do not write invalid iOS date JSON;
  - patient record editing now uses option controls for picker/segmented/multi-select, DatePicker for dates, ICD-10 search picker/list, and filters fields by `visibleWhen` before saving;
  - template save now sanitizes empty option fields and prevents required PHOTO from making a template impossible to submit;
  - patient record scanning now returns a scan status so the list can distinguish missing/unreadable storage from truly empty records;
  - malformed custom template files now surface a warning in the template list instead of silently disappearing.
- 2026-06-03: Re-ran verification after final Sentinel fixes:
  - `JAVA_HOME=/Applications/Android Studio.app/Contents/jbr/Contents/Home ./gradlew testDebugUnitTest --tests com.tsinghua.sample.patient.PatientTemplateModelTest` passed;
  - `JAVA_HOME=/Applications/Android Studio.app/Contents/jbr/Contents/Home ./gradlew testDebugUnitTest assembleDebug` passed;
  - `find app/src/main/res/layout -name '*.xml' -print0 | xargs -0 xmllint --noout` passed;
  - `swift docs/verification/verify_ios_patient_json.swift` passed with `iOS patient JSON decode OK`.
- 2026-06-03: User reported that ECG/IMU/SpO2/ring/audio cards appeared missing on device. Investigation found `ListActivity` still creates all five device entries and binds `DeviceAdapter`; real-device UI dump showed the list area was initially empty during the permission flow, then user granted runtime permissions and all modules appeared. Current conclusion: this was a permission/initialization presentation issue, not deletion or regression of device modules.
- 2026-06-03: User requested the built-in Android template include the jaundice value found in the iOS obstetrics/newborn template, and asked for UI optimization ideas because the Android UI is less attractive/intuitive than iOS.
- 2026-06-03: Added `bilirubinLevel` to the Android built-in `cardio_cerebrovascular` template under `vital_signs`, matching the iOS field id, label, decimal type, placeholder, and `mg/dl` unit while keeping Android's single built-in template policy unchanged.
- 2026-06-03: Verification after jaundice field update passed: `JAVA_HOME=/Applications/Android Studio.app/Contents/jbr/Contents/Home ./gradlew testDebugUnitTest assembleDebug`.
- 2026-06-03: UI review finding: iOS patient/template/record screens lean on native `Form/List/Section`, with clear grouped information architecture. Android currently mixes a tall main capture screen bottom bar, card-heavy dynamic form sections, and several programmatic ScrollView screens; the biggest intuitive gaps are hierarchy, button density, inline affordances, and lack of list/section consistency.

## Final Sentinel Report

Raw Sentinel output from the successful third review:

```text
Sentinel prompt read OK: `/Users/panda/.Codex/agents/sentinel.md`.

`~/Desktop/program-dev-common-pitfalls.md` 原路径不可读：`No such file or directory`。我找到了备份副本并只作为辅助清单参考，但原指定路径确实不可读。

## 阶段 1：相关类判定
- 主要：A 用户路径/表单 + B JSON 契约 + C 本地数据一致性 + H 边界错误
- 横切：L PII residual + T 测试缺口 + P iOS 兼容
- 未涉及：D 资金、E 部署基础设施、G 并发核心链路

## Findings

[高] Date 编辑可写入非 ISO，iOS 会丢记录
[PatientRecordsActivity.java](/Users/panda/AndroidStudioProjects/Sample/main/app/src/main/java/com/tsinghua/sample/activity/PatientRecordsActivity.java:168) 用普通 `EditText` 编辑日期；[PatientRecordsActivity.java](/Users/panda/AndroidStudioProjects/Sample/main/app/src/main/java/com/tsinghua/sample/activity/PatientRecordsActivity.java:258) 直接保存任意非空文本为 `FieldValue.date`；[PatientValueFormatter.java](/Users/panda/AndroidStudioProjects/Sample/main/app/src/main/java/com/tsinghua/sample/patient/model/PatientValueFormatter.java:29) 对非 `yyyy-MM-dd` 输入原样保留。影响：用户输入 `2026/06/03` 这类值后，iOS `Date` 解码失败，`PatientRecordsView` 会跳过该 JSON。

[中] 患者记录编辑页未按 iOS 动态控件对齐
[PatientRecordsActivity.java](/Users/panda/AndroidStudioProjects/Sample/main/app/src/main/java/com/tsinghua/sample/activity/PatientRecordsActivity.java:157) 把 picker/segmented/ICD10/text 等多数类型都退化成文本框；[PatientRecordsActivity.java](/Users/panda/AndroidStudioProjects/Sample/main/app/src/main/java/com/tsinghua/sample/activity/PatientRecordsActivity.java:220) 保存时也不按 `visibleWhen` 跳过隐藏字段。影响：可保存非法选项、非法 ICD10 文本、隐藏字段旧值，和 iOS 记录编辑能力不等价。

[中] 模板编辑可生成无法填写/提交的模板
[TemplateEditorActivity.java](/Users/panda/AndroidStudioProjects/Sample/main/app/src/main/java/com/tsinghua/sample/activity/TemplateEditorActivity.java:162) 允许任意字段设必填，[TemplateEditorActivity.java](/Users/panda/AndroidStudioProjects/Sample/main/app/src/main/java/com/tsinghua/sample/activity/TemplateEditorActivity.java:196) 允许选项型字段保存空选项；而 [PatientInfoActivity.java](/Users/panda/AndroidStudioProjects/Sample/main/app/src/main/java/com/tsinghua/sample/activity/PatientInfoActivity.java:250) 空选项不会产生可填写值，PHOTO 字段也不进入 `fields`。影响：必填 picker/segmented/multiSelect/photo 可能永远过不了校验。

[中] 患者记录列表缺少存储权限失败提示
[PatientStoragePaths.java](/Users/panda/AndroidStudioProjects/Sample/main/app/src/main/java/com/tsinghua/sample/patient/storage/PatientStoragePaths.java:22) 扫描公共 `Movies/FacialCollection`，但 [PatientRecordRepository.java](/Users/panda/AndroidStudioProjects/Sample/main/app/src/main/java/com/tsinghua/sample/patient/storage/PatientRecordRepository.java:69) 只返回空列表/跳过异常。影响：Android 11+ 缺少外部目录权限时，用户只看到“暂无患者记录”，无法区分无数据和无权限。

[低] 模板损坏被静默吞掉
[TemplateRepository.java](/Users/panda/AndroidStudioProjects/Sample/main/app/src/main/java/com/tsinghua/sample/patient/storage/TemplateRepository.java:125) 读取自定义模板失败后直接忽略。影响：模板 JSON 一旦写坏或手工编辑出错，用户侧表现为模板消失，没有恢复/告警线索。

## Residual risks / test gaps
- 需要真机端到端验证：前/后摄、post-process、血氧仪、ECG、ring、IMU、audio 录制后目录和数据仍正常。
- Swift 验证脚本目前是硬编码样例，不是实际 Android 生成/编辑后的 JSON。
- 需要覆盖 Android 11/13/14 权限场景：保存 JSON、拍照、记录扫描。
- 需要手工测模板编辑边界：空选项、必填照片、条件字段、删除自定义模板后编辑旧记录。
- PII 明文外部目录按本轮场景接受，仍是 residual risk。
```

## Open Questions

1. No active compile/test blocker. Android SDK is installed under `~/Library/Android/sdk`; use Android Studio JBR for Gradle commands.
2. UI optimization route needs user decision before implementation:
   - Option A: light polish only, keep current screen structure and improve spacing, labels, button hierarchy, active badges, and empty/error states.
   - Option B: iOS-style grouped form/list rewrite for patient info, records, templates, and editor, while keeping main capture screen mostly stable.
   - Option C: broader main capture screen redesign with a compact action rail/bottom navigation and stronger device-status overview, requiring the most device smoke testing.
- 2026-06-03: User selected Option B. Execution plan:
  1. Add a small shared Android grouped UI helper for consistent background, section headers, grouped cards, metadata rows, chips, and action rows.
  2. Restyle `PatientInfoActivity` as grouped sections with clearer session summary and sticky-feeling primary/secondary action hierarchy.
  3. Restyle `PatientRecordsActivity` as searchable grouped list cards with explicit metadata and clearer empty/error state.
  4. Restyle `TemplateListActivity` as built-in/custom grouped template list with active/built-in chips and clearer actions.
  5. Restyle `TemplateEditorActivity` as grouped editor sections with field rows that read like iOS form rows.
  6. Keep `ListActivity` recording/device logic stable; avoid changing camera, ECG, IMU, SpO2, ring, and audio implementation paths.
  7. Verify with unit tests, debug build, XML syntax, and real-device smoke for page launch/navigation.
- 2026-06-03: Option B implementation status:
  - Added shared `GroupedUi` helper for grouped backgrounds, section headers, cards, metadata rows, chips, and action buttons.
  - Restyled `PatientInfoActivity` dynamic sections and `activity_patient_info.xml` session summary/actions toward iOS-style grouped form hierarchy.
  - Restyled `PatientRecordsActivity` list cards, empty states, and edit dialog sections.
  - Restyled `TemplateListActivity` with grouped header, active/built-in chips, clearer structure metadata, and grouped actions.
  - Restyled `TemplateEditorActivity` with grouped template info, action card, section cards, and field rows.
  - Did not change camera, ECG, IMU, SpO2, ring, audio recording implementations.
- 2026-06-03: Verification after Option B UI implementation:
  - `JAVA_HOME=/Applications/Android Studio.app/Contents/jbr/Contents/Home ./gradlew testDebugUnitTest assembleDebug` passed.
  - `find app/src/main/res/layout -name '*.xml' -print0 | xargs -0 xmllint --noout` passed.
  - Real-device smoke was attempted, but `adb devices -l` returned no connected devices at that moment.
- 2026-06-03: Post-Option-B Sentinel follow-up fixes completed:
  - template sanitization now clears required flags for blood pressure and photo fields, and for option fields whose only option is `未填写`;
  - patient creation and record editing refresh conditional visibility after text, date, option, ICD10, and blood-pressure component changes;
  - ICD10 diagnosis lists in patient creation and record editing now support removing mistaken selections;
  - template editor disables required selection for blood pressure/photo fields and only closes dialogs after successful save;
  - custom template card actions now wrap into multiple action rows instead of squeezing five actions into one narrow row.
- 2026-06-03: Verification after Post-Option-B fixes:
  - `JAVA_HOME=/Applications/Android Studio.app/Contents/jbr/Contents/Home ./gradlew testDebugUnitTest assembleDebug` passed;
  - `find app/src/main/res/layout -name '*.xml' -print0 | xargs -0 xmllint --noout` passed;
  - `~/Library/Android/sdk/platform-tools/adb devices -l` reported no attached devices, so real-device smoke remains pending.

## Risk Controls

- Keep camera/video/SpO2/ECG/ring/IMU/audio implementation files untouched unless a compile error requires a narrow import/navigation change.
- Prefer adding patient/template modules instead of rewriting recording modules.
- Maintain current post-recording handoff: recording stop still calls patient info screen after post-processing.
- Use unit tests for serialization and repositories before UI implementation.
- Run compile/test before final.
- Spawn Sentinel review and present its report before considering the task complete.

## 2026-06-03 Follow-Up UX/Fit Issues

User-reported issues after grouped UI work:

- Main camera card sits too close to the top/status bar on a punched-hole phone.
- Camera preview display has large black side bars; final recording content is OK, so the fix should stay display-only.
- Patient records page lacks delete.
- Custom template save button saves but does not return automatically.
- Field editor UI is too plain/confusing; users cannot easily tell each field type, option behavior, or conditional display semantics.

Implementation status:

- [x] Root cause identified for preview: `mainCameraSurfaceView` was constrained to a narrow `9:16` surface inside a wide fixed-height black preview container.
- [x] Main capture page changed to avoid edge-to-edge status-bar overlap and use `fitsSystemWindows`; top card spacing increased.
- [x] Main preview surface now fills the preview display area; CameraHelper/processor recording paths remain unchanged.
- [x] Patient record repository now supports deleting the whole corresponding `Session_<digits>` capture session folder, including patient JSON, referenced photos, videos, and device subdirectories.
- [x] Added unit tests that whole-session deletion removes capture files, rejects malformed/non-session directories, rejects sessions outside the scan root, refuses symlink escape paths, and migrates legacy text edits into `PatientInfo/*.json`.
- [x] Patient records cards now show explicit edit/delete actions; edit dialog also has delete with confirmation.
- [x] Template editor main save action now saves successfully and returns to the previous screen; internal field/section edits still save-and-refresh.
- [x] Field editor dialog restyled into grouped sections with type descriptions, option section only for option-based fields, and conditional display based on a field picker instead of hand-entered field IDs.
- [x] Full regression verification passed locally:
  - `JAVA_HOME=/Applications/Android Studio.app/Contents/jbr/Contents/Home ./gradlew testDebugUnitTest assembleDebug`
  - `find app/src/main/res/layout -name '*.xml' -print0 | xargs -0 xmllint --noout`

Risk controls for this follow-up:

- Camera/video output, ECG, IMU, SpO2, ring, and audio recording implementation logic must remain untouched.
- Patient delete now intentionally removes the whole corresponding `Session_<digits>` directory per user request; guardrails require a valid session name, root containment, and no symlink escape before recursive deletion.
- Preview visual changes are treated as display-only and must not alter video writer/encoder behavior.

## 2026-06-03 Final Delete/Sentinel Closure

- Latest user decision supersedes the earlier conservative delete behavior: deleting a patient record should delete the corresponding whole session folder for convenience.
- Final Sentinel high-risk findings and fixes:
  - Legacy `info/subject_info*.txt` record edits now save into `PatientInfo/*.json`, so edited legacy records remain visible on rescan.
  - Whole-session delete now requires `Session_<digits>`, rejects directories outside the configured `FacialCollection` root, and refuses directories containing symlinks that would escape the session tree.
  - Delete safety tests now cover malformed session names, non-session directories, outside-root sessions, symlink escape, and the expected full-session deletion of video/photo/patient files.
- Additional local fixes from Sentinel:
  - legacy session timestamps parse to ISO-like strings;
  - custom template writes use temp-file atomic move;
  - duplicate custom field IDs are renamed during template sanitization;
  - delete refusal toast states the session was not deleted because the directory is missing or outside the capture root.
- Final verification after these fixes:
  - `JAVA_HOME=/Applications/Android Studio.app/Contents/jbr/Contents/Home ./gradlew testDebugUnitTest --tests com.tsinghua.sample.patient.PatientTemplateModelTest`
  - `JAVA_HOME=/Applications/Android Studio.app/Contents/jbr/Contents/Home ./gradlew testDebugUnitTest assembleDebug`
  - `find app/src/main/res/layout -name '*.xml' -print0 | xargs -0 xmllint --noout`
  - `~/Library/Android/sdk/platform-tools/adb -s 192.168.31.78:40169 install -r app/build/outputs/apk/debug/app-debug.apk`
  - `~/Library/Android/sdk/platform-tools/adb -s 192.168.31.78:40169 shell am start -W -n com.tsinghua.sample/.activity.ListActivity`
