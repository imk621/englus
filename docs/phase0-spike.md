# Phase 0 스파이크 — 온디바이스 ASR 검증

[../PLAN.md](../PLAN.md)의 Phase 0을 실행하는 최소 앱. **기능 앱이 아니다.**
목적은 딱 하나를 판정하는 것:

> Galaxy S25에서 whisper.cpp가 **팟캐스트 길이의 오디오를 백그라운드 작업으로 쓸 만한 속도로**,
> **단어 단위 타임스탬프까지** 뽑아내는가?

이게 되면 계획 전체가 성립한다. 안 되면 PLAN.md의 플랜 B(대본 제공 콘텐츠 전용)로 축소한다.

## 측정하는 것

| 항목 | 어디서 보나 | 판정 기준 |
|---|---|---|
| 전사 속도 | 앱 상단 PASS/FAIL 카드, `rtf` / `speedX` | **10분 오디오 → 60초 이내** |
| 단어 타임스탬프 정확도 | `json2srt.py --words`로 SRT 생성 후 플레이어에서 확인 | 들리는 단어와 ±100ms 이내 |
| 전사 정확도(WER) | `wer.py`로 공개 대본과 대조 | <10% 사용 가능 / >20% 사용 불가 |
| 스레드 확장성 | 스레드 슬라이더를 바꿔가며 재실행 | 어느 지점에서 포화되는지 |
| 배터리·발열 | 결과 카드의 배터리/온도 델타 | 10분 전사에 몇 %인지 |
| 모델 트레이드오프 | base.en vs small.en 비교 | 속도와 WER 중 어디서 만나는지 |

## 준비

```bash
cd spike

# 1. whisper.cpp 복원 (pin된 커밋, 저장소에는 커밋하지 않음)
./tools/setup.sh

# 2. 빌드해서 폰에 설치 — 반드시 release. debug 빌드의 네이티브 성능 숫자는 무의미하다.
./gradlew :bench:installRelease

# 3. 모델 내려받아 폰으로 push
./tools/fetch-model.sh base.en
./tools/fetch-model.sh small.en
```

오디오는 폰의 아무 곳(다운로드 폴더 등)에 두고 앱에서 파일 선택기로 고른다.
mp3 / m4a / opus / wav 등 플랫폼이 디코딩할 수 있는 형식이면 된다.

## 실행 순서

1. 모델 선택 → 오디오 선택 → 스레드 수 설정 → **전사 실행**
2. 상단 카드에서 PASS/FAIL 및 `10분 환산 N초` 확인
3. **리포트 저장** 누르면 JSON + TXT가 기기에 기록됨
4. 결과 회수 후 분석:

```bash
adb pull /sdcard/Android/data/com.englus.spike/files/results ./results

# 타임스탬프 눈으로 검증 (오디오와 함께 플레이어에 로드)
python tools/json2srt.py results/<run>.json --words

# 전사 정확도 (VOA/BBC 공개 대본 필요)
python tools/wer.py reference.txt results/<run>.txt
```

## 에뮬레이터 검증 (선택)

파이프라인이 **동작하는지**는 폰 없이도 확인할 수 있다. 단 **성능은 확인할 수 없다** —
에뮬레이터 수치는 이 PC의 CPU를 재는 것이지 Snapdragon을 재는 게 아니다.

```bash
# AVD 부팅 (한 번 만들어두면 재사용)
$ANDROID_HOME/emulator/emulator -avd englus_test -no-window -no-audio -no-boot-anim &

# 빌드 + 설치 + 모델 push + 계측 테스트
./tools/run-tests.sh
```

검증되는 것: JNI 로딩, MediaCodec 디코딩, 리샘플러의 청크 경계 정확도,
whisper 전사 결과, **DTW 토큰 타임스탬프 생성**.

실제 기기에서도 같은 테스트를 돌릴 수 있다:

```bash
ABI=arm64-v8a MODEL=base.en ./tools/run-tests.sh
```

> `gradlew connectedAndroidTest`는 쓰지 말 것. 앱을 언인스톨하면서 모델까지 지워
> 전사 테스트가 조용히 SKIP되는데, assumption 실패는 통과로 집계되어 요약은
> `OK`로 나온다. `run-tests.sh`는 이 경우를 명시적 실패로 처리한다.

## 첫 실험 권장 세트

| # | 오디오 | 모델 | 스레드 | 보는 것 |
|---|---|---|---|---|
| 1 | VOA Learning English 5분 (대본 있음) | base.en | 4 | 기준선 + WER |
| 2 | 같은 오디오 | base.en | 8 | 스레드 포화 지점 |
| 3 | 같은 오디오 | small.en | 4 | 속도/정확도 트레이드오프 |
| 4 | 일반 팟캐스트 20~30분 | 위 승자 | 위 최적 | **실사용 조건**, 배터리·발열·메모리 |

3번까지는 예측 가능하고, **판정을 가르는 건 4번**이다. 짧은 샘플에서 잘 나오다가
긴 파일에서 스로틀링으로 무너지는 게 모바일 추론의 전형적인 실패 모드다.

## 설계 메모

- **arm64-v8a 전용, `-march=armv8.2-a+fp16`.** S25는 armv8.2 이상이고 네이티브 fp16
  연산이 추론 속도의 큰 부분을 좌우한다. 베이스라인 armv8-a로 재면 실제보다 느리게 나온다.
- **디코딩과 리샘플링을 한 패스로 융합.** 전부 디코딩 후 리샘플하는 방식은 48kHz 30분
  오디오에서 float 345MB를 들고 있게 되어 OOM이 난다. ([AudioDecoder.kt](app/src/main/java/com/englus/spike/AudioDecoder.kt))
- **DTW aheads 프리셋은 모델 파일명에서 유도.** 잘못된 프리셋은 에러가 아니라 *그럴듯하지만
  틀린* `t_dtw`를 낸다 — 타임스탬프 스파이크에서 가장 나쁜 결과라서 UI에 무엇을 골랐는지 표시한다.
- **temperature fallback 끔.** 재시도가 섞이면 측정한 벽시계 시간이 정상 상태 비용을
  대표하지 못한다.
- **결과를 JSON으로 반환.** JNI 표면을 4개 함수로 줄이고, 같은 payload를 그대로 파일로
  떨궈 오프라인 분석에 쓴다.
