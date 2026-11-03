# 개발 환경 (2026-11)

| 항목 | 버전 |
|---|---|
| JDK | 21 (Temurin 21.0.13) |
| Gradle | 9.8.2 (wrapper) |
| AGP | 9.5.0, 내장 Kotlin (모듈에 `org.jetbrains.kotlin.android` 없음) |
| Kotlin / KSP | 2.4.21 / 2.3.13 |
| compileSdk / targetSdk / minSdk | 37 / **36** (S25 = Android 16, One UI 8) / 26 |
| NDK / CMake | 27.2.12479018 / 3.22.1 (`:asr` whisper.cpp JNI) |

compileSdk 37은 compose-bom 2026.09, core 1.19, okhttp 5 같은 최신 라이브러리가 요구해서 올렸다.
런타임 동작은 targetSdk가 정하므로 S25에 맞춰 36으로 둔다.

## 처음 받았을 때

```bash
tools/setup.sh                                   # whisper.cpp 를 고정 커밋으로 복원
echo "sdk.dir=$ANDROID_HOME" > local.properties
./gradlew :app:assembleDebug -Pabis=x86_64       # 에뮬레이터용
./gradlew :app:assembleRelease                   # S25 용 (arm64-v8a 만)
```

Ubuntu 개발 PC에서는 JDK·SDK·NDK·에뮬레이터·Gradle 캐시를 모두 `/media/user/ssd_space/android-dev`에 두고,
`source /media/user/ssd_space/android-dev/env.sh`로 환경을 잡는다. 안내는 그 폴더의 `README.md`에 있다.

## 에뮬레이터 (S25 대역)

AVD `S25_API36`: API 36 Google Play x86_64 이미지, 1080×2340, 420dpi, RAM 4GB.
Play Store 이미지를 쓰는 이유는 ML Kit 번역·OCR이 Play 서비스를 요구하기 때문이다.

에뮬레이터로 **확인할 수 없는 것**이 있다:
- **Gemini Nano(AICore)**: 에뮬레이터에 없어서 설정에 "지원하지 않음"으로 뜨고, 앱은 클라우드 경로로 동작한다.
- **whisper 속도**: x86_64 빌드는 PC의 CPU 속도를 잰다. S25 수치는 `:bench`를 arm64 release로 실기에서 돌려서 얻는다.
- 마이크·카메라 품질, 발열, 배터리.

## 온디바이스 AI를 PC GPU로 미리 보기 — `tools/hosteval`

RTX 3070이 S25를 대신할 수 있는 범위와 없는 범위:

| 질문 | PC GPU로 답할 수 있나 |
|---|---|
| 같은 프롬프트에 작은 모델이 **형식에 맞는 답**(JSON, 필수 키)을 내는가 | **예.** 형식 실패는 칩이 아니라 모델과 프롬프트의 문제다 |
| 답의 **내용 품질**(설명이 맞는가, 교정이 최소인가) | **대략.** 같은 체급의 공개 모델을 대역으로 쓴다 |
| S25에서 **몇 초** 걸리나, 발열·배터리는 | **아니요.** 메모리 대역폭, NPU 유무, 스로틀링이 다르다. 실기에서 잰다 |
| Gemini Nano **그 자체**의 품질 | **아니요.** 가중치가 공개되지 않았다. 공개된 형제는 Gemma 3n |

```bash
tools/hosteval/run.sh                                 # 기본: Qwen3-VL-2B (이 PC에 받아 둔 것)
MODEL=google/gemma-3n-E2B-it tools/hosteval/run.sh    # 받아 두었다면 Nano 형제로
```

프롬프트는 Python 사본을 두지 않는다. `HostEvalPromptsTest`가 앱의 Kotlin 빌더로
**폰이 Nano에 보낼 문자열 그대로**를 만들어 덤프한다(`JsonText.onDevicePrompt`). 채점 기준은
`JsonText.extract`·`satisfies`와 각 화면 파서의 조건이다. 결과는 `results/hosteval/<시각>/`에 남는다.

### 첫 측정 (2026-11-03, Qwen3-VL-2B bf16, RTX 3070, 탐욕 디코딩)

| 작업 | 형식 충족 | 내용 |
|---|---|---|
| 영작 첨삭 | 6/6 | 교정 자체는 맞음. 단, 한국어로 쓰라는 설명을 영어로 썼고, 오류 여러 개를 한 항목으로 뭉쳤다 |
| 문장 설명 | 5/8 | 실패 3건은 같은 구절을 반복하다 1024토큰 상한에 닿았다. 성공 5건도 `translation`에 원문 영어를 그대로 넣는 등 쓸 수 없었다 |
| 듣기 시험 | 0/1 | 보기 4개가 같은 문장이었고, 반복하다 4096토큰 상한에 닿았다 |

읽는 법: 2B 대역은 **짧은 구조화 출력은 따르지만, 긴 한국어 생성은 반복에 빠진다.**
앱은 이 경우 클라우드로 넘어가므로 오동작은 없다. 하지만 Nano가 같은 식으로 실패하면 넘어가기 전에
수십 초를 허비한다. 시험을 클라우드 우선으로 둔 판단은 이 측정과 맞는다.
다음 측정은 Nano의 공개 형제인 Gemma 3n으로 한다(테더링이 아닐 때 받을 것). 그 결과에 따라
"형식은 맞지만 한국어가 아닌 답"을 걸러내는 내용 검사를 `AiRouter`에 넣을지 정한다.
