# 来源与第三方声明 / Provenance and third-party notices

## 新增代码 / Newly authored code

新增 Android Kotlin 代码、测试、构建脚本及文档采用根目录 LICENSE 中的 MIT 条款。该许可明确排除导入代码、图形及第三方组件，不代表全部资源可按 MIT 再分发。

New Android Kotlin code, tests, build scripts and documentation use the MIT terms in LICENSE. Imported material and third-party components are excluded; the entire asset bundle is not MIT licensed.

## 导入资源 / Imported material

- Source: https://github.com/xlle-er/LOOI_DF26 and https://github.com/xlle-er/LOOI-DF26-IOS . iOS snapshot commit: `c61b26df3bbca6037723fca070dae5abde3b3235`.
- `FaceSource/`: original eyes, command queue, action catalog and choreography; Android host/message adaptation is recorded in the files. `android-host.css` is newly authored. `ReferenceSource/`: web personality, idle rules, tracking and relative-follow reference, snapshot 2026-10-15 including local edits.
- Generated derivatives: `app/src/main/assets/face/`, `live-setup.json`, `scenarios.json`, `personality.json`, `idle-scenarios.json`. Derivatives retain their source's rights.
- `icon.png` and generated launcher artwork: supplied project icon; no separate artwork or trademark license is asserted.
- **Upstream repositories did not declare a license at publication. Their code/artwork is not relicensed here. Permission beyond the original owner's rights must be obtained from the relevant rights holder.** This is a mixed-license/source-available distribution, with the new Android implementation under MIT.
- 上游未声明许可证；本次保留来源，不替上游或其他权利人授权。完整项目属于混合授权/源码可查看分发，新增 Android 实现采用 MIT。图标与商标权利保留。

SHA-256 resource provenance is in `docs/resource-hashes.json`. No original repository Git history, private settings or credentials are imported.

## Android runtime / 运行依赖

| Component | Terms / Source |
| --- | --- |
| Kotlin, kotlinx.coroutines | Apache-2.0; https://github.com/JetBrains/kotlin ; https://github.com/Kotlin/kotlinx.coroutines |
| AndroidX Compose, Lifecycle, DataStore, WebKit, CameraX | Apache-2.0; https://developer.android.com/jetpack/androidx |
| OkHttp / Okio | Apache-2.0; https://github.com/square/okhttp ; https://github.com/square/okio |
| MediaPipe Tasks Vision 0.10.29 | Apache-2.0; https://github.com/google-ai-edge/mediapipe |
| TensorFlow Lite and native dependencies | Apache-2.0 and bundled BSD/MIT notices; https://github.com/tensorflow/tensorflow |
| XNNPACK, cpuinfo, pthreadpool, libyuv, protobuf | Retain their upstream BSD-style licenses; texts in `licenses/` |
| Google Play services authorization | Google APIs / Android SDK terms, not covered by MIT; https://developers.google.com/terms ; https://developer.android.com/studio/terms |

## Local model / 本地模型

EfficientDet-Lite0 int8, version 1, unmodified Google model:
https://storage.googleapis.com/mediapipe-models/object_detector/efficientdet_lite0/int8/1/efficientdet_lite0.tflite

Model family / license listing: https://www.kaggle.com/models/tensorflow/efficientdet/tfLite/lite0-int8/1 (Apache-2.0). Guide: https://ai.google.dev/edge/mediapipe/solutions/vision/object_detector .

SHA-256: `0720bf247bd76e6594ea28fa9c6f7c5242be774818997dbbeffc4da460c723bb`.
Model is based on COCO labels; no COCO training images are distributed. Model accuracy and physical following have not been validated on a real device.

## Build and test tools / 构建测试工具

- Gradle wrapper: Apache-2.0, https://github.com/gradle/gradle . Original wrapper notices are retained.
- Android Gradle Plugin: Apache-2.0 / Android SDK terms as applicable; https://developer.android.com/build .
- esbuild: MIT, https://github.com/evanw/esbuild .
- Playwright: Apache-2.0, https://github.com/microsoft/playwright .
- JUnit: EPL-1.0, https://github.com/junit-team/junit4 . Only test dependency, not app runtime.
- GoogleSignIn-iOS / Swift are not Android dependencies.

Full license texts collected from upstream are in `licenses/`, with retrieval URLs in `licenses/sources.json`. Existing dependency notices inside the APK remain in place. A copy of these notices is also included in APK assets and the Release notices ZIP. Transitive dependencies retain their own terms; these notices do not override them or grant trademark rights. Build tools/SDKs are not redistributed as source archives.
