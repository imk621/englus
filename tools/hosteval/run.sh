#!/usr/bin/env bash
# PC GPU 로 앱의 온디바이스 프롬프트를 재생해 품질을 본다.
#
#   tools/hosteval/run.sh                                  # Qwen3-VL-2B (이미 받아 둔 것, 다운로드 0)
#   MODEL=google/gemma-3n-E2B-it tools/hosteval/run.sh     # Nano 의 공개 형제 (받아 둔 경우)
#   BACKEND=ollama MODEL=gemma3n:e2b tools/hosteval/run.sh # ollama 서버 사용
#
# 1) 앱의 Kotlin 빌더가 만든 프롬프트를 그대로 덤프 (HostEvalPromptsTest)
# 2) GPU 에서 생성 → JSON 추출·스키마·앱 파서 조건으로 채점
# 결과: results/hosteval/<시각>/<모델>.md, .jsonl (gitignore 됨)
set -euo pipefail
cd "$(dirname "$0")/../.."
source /media/user/hdd_space/dev_lib/android-dev/env.sh

# CUDA PyTorch + transformers 가 갖춰진 기존 환경과 모델 캐시를 빌려 쓴다.
PY="${PY:-/media/user/ssd_space/titan_failcase_ai/venv/bin/python}"
export HF_HOME="${HF_HOME:-/media/user/ssd_space/titan_failcase_ai/hf}"
# 허브 확인 통신까지 막는다: 테더링에서 모르는 사이에 받는 일이 없게.
export HF_HUB_OFFLINE="${HF_HUB_OFFLINE:-1}"

OUT="results/hosteval/$(date +%Y%m%d-%H%M%S)"
mkdir -p "$OUT"
HOSTEVAL_OUT="$PWD/$OUT/prompts.jsonl" ./gradlew -q --console=plain \
  :app:testDebugUnitTest --tests '*HostEvalPromptsTest*' --rerun

"$PY" -I tools/hosteval/hosteval.py \
  --prompts "$OUT/prompts.jsonl" --out "$OUT" \
  --backend "${BACKEND:-hf}" --model "${MODEL:-Qwen/Qwen3-VL-2B-Instruct}" --dtype "${DTYPE:-bf16}"
