#!/usr/bin/env python3
"""Replay the app's on-device prompts on the PC GPU and score the replies.

What this answers: does a phone-class (~2-4B) model, given exactly the text
Gemini Nano would get, return something the app can use, or would the app fall
back to the cloud? It measures FORMAT and CONTENT quality, which depends on the
model and prompt and not on the chip.

What it does not answer: S25 speed, battery or heat. An RTX 3070 is a different
machine (different memory bandwidth, no NPU), so its latency is printed for
comparison between models on this PC only. Measure the phone on the phone.

The model is a stand-in. Gemini Nano's weights are not public. Its open sibling
is Gemma 3n; Qwen3-VL-2B is what is already on this PC. A prompt that a 2B
stand-in cannot follow is a prompt worth fixing before it reaches Nano, but a
pass here does not mean Nano passes.

    tools/hosteval/run.sh                      # dumps prompts, runs default model
    python hosteval.py --prompts P --out DIR --model Qwen/Qwen3-VL-2B-Instruct
"""
import argparse
import json
import statistics
import time
from pathlib import Path


# --- validation: mirrors app/src/main/java/com/englus/app/ai/JsonText.kt -------

def extract(reply: str):
    start, end = reply.find("{"), reply.rfind("}")
    if start < 0 or end <= start:
        return None
    try:
        return json.loads(reply[start:end + 1])
    except json.JSONDecodeError:
        return None


def satisfies(value: dict, schema: dict) -> bool:
    props = schema.get("properties", {})
    for key in schema.get("required", []):
        if key not in value or value[key] is None:
            return False
        sub = props.get(key, {})
        t = sub.get("type", "").upper()
        if t == "OBJECT" and (not isinstance(value[key], dict) or not satisfies(value[key], sub)):
            return False
        if t == "ARRAY" and not isinstance(value[key], list):
            return False
    return True


def task_checks(task: str, v: dict) -> list[str]:
    """Checks the app's own parsers apply after the shape, so a pass here is a pass there."""
    problems = []
    if task == "EXAM":
        qs = v.get("questions") or []
        if not qs:
            problems.append("문제 0개")
        for q in qs:
            opts = q.get("options") or []
            if len(opts) != 4:
                problems.append(f"보기 {len(opts)}개")
            ci = q.get("correctIndex")
            if not isinstance(ci, int) or not 0 <= ci < max(len(opts), 1):
                problems.append(f"correctIndex={ci}")
    if task == "EXPLAIN":
        for k in ("translation", "sound"):
            if not str(v.get(k, "")).strip():
                problems.append(f"{k} 비어 있음")
    if task == "WRITING_CORRECTION":
        if not str(v.get("corrected", "")).strip():
            problems.append("corrected 비어 있음")
    return problems


# --- backends ----------------------------------------------------------------

class HfBackend:
    """transformers on CUDA. Greedy decoding so runs are comparable."""

    def __init__(self, model_id: str, dtype: str):
        import torch
        from transformers import AutoModelForImageTextToText, AutoProcessor, AutoModelForCausalLM, AutoTokenizer
        self.torch = torch
        kw = {"device_map": "cuda"}
        if dtype == "4bit":
            from transformers import BitsAndBytesConfig
            kw["quantization_config"] = BitsAndBytesConfig(load_in_4bit=True, bnb_4bit_compute_dtype=torch.bfloat16)
        else:
            kw["dtype"] = torch.bfloat16
        t0 = time.time()
        try:  # VL models (Qwen3-VL, Gemma 3n) take text-only chats through the processor.
            self.proc = AutoProcessor.from_pretrained(model_id)
            self.model = AutoModelForImageTextToText.from_pretrained(model_id, **kw).eval()
            self.text_only = False
        except (ValueError, KeyError):
            self.proc = AutoTokenizer.from_pretrained(model_id)
            self.model = AutoModelForCausalLM.from_pretrained(model_id, **kw).eval()
            self.text_only = True
        self.load_s = time.time() - t0
        self.name = model_id

    def generate(self, prompt: str, max_new_tokens: int):
        torch = self.torch
        content = prompt if self.text_only else [{"type": "text", "text": prompt}]
        inp = self.proc.apply_chat_template(
            [{"role": "user", "content": content}], tokenize=True, add_generation_prompt=True,
            return_dict=True, return_tensors="pt").to("cuda")
        n_in = inp["input_ids"].shape[1]
        torch.cuda.synchronize()
        t0 = time.time()
        with torch.no_grad():
            out = self.model.generate(**inp, max_new_tokens=max_new_tokens, do_sample=False)
        torch.cuda.synchronize()
        dt = time.time() - t0
        new = out[0][n_in:]
        dec = self.proc if self.text_only else self.proc.tokenizer
        text = dec.decode(new, skip_special_tokens=True)
        return text, {"in_tokens": n_in, "out_tokens": int(new.shape[0]), "seconds": dt}

    def vram_gb(self):
        return self.torch.cuda.max_memory_allocated() / 1e9


class OllamaBackend:
    """An ollama server (tools: hdd_space/dev_lib/android-dev/bin/hosteval-ollama). Only useful once it sees the GPU."""

    def __init__(self, model: str, url: str):
        import urllib.request
        self.req, self.model, self.url, self.name, self.load_s = urllib.request, model, url, f"ollama:{model}", 0.0

    def generate(self, prompt: str, max_new_tokens: int):
        body = json.dumps({"model": self.model, "prompt": prompt, "stream": False,
                           "options": {"temperature": 0, "num_predict": max_new_tokens}}).encode()
        r = self.req.Request(f"{self.url}/api/generate", body, {"Content-Type": "application/json"})
        t0 = time.time()
        res = json.load(self.req.urlopen(r, timeout=600))
        return res.get("response", ""), {"in_tokens": res.get("prompt_eval_count", 0),
                                         "out_tokens": res.get("eval_count", 0), "seconds": time.time() - t0}

    def vram_gb(self):
        return float("nan")


# --- run ---------------------------------------------------------------------

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--prompts", required=True)
    ap.add_argument("--out", required=True)
    ap.add_argument("--backend", choices=["hf", "ollama"], default="hf")
    ap.add_argument("--model", default="Qwen/Qwen3-VL-2B-Instruct")
    ap.add_argument("--dtype", choices=["bf16", "4bit"], default="bf16")
    ap.add_argument("--ollama-url", default="http://127.0.0.1:11435")
    # Nano's real output cap is device-dependent; 1024 matches TextRequest's default.
    ap.add_argument("--max-new-tokens", type=int, default=1024)
    a = ap.parse_args()

    rows = [json.loads(l) for l in Path(a.prompts).read_text().splitlines() if l.strip()]
    be = HfBackend(a.model, a.dtype) if a.backend == "hf" else OllamaBackend(a.model, a.ollama_url)
    out_dir = Path(a.out)
    out_dir.mkdir(parents=True, exist_ok=True)
    tag = a.model.replace("/", "_") + ("" if a.backend == "hf" else "_ollama") + ("_4bit" if a.dtype == "4bit" else "")
    results = []
    with open(out_dir / f"{tag}.jsonl", "w") as f:
        for r in rows:
            # EXAM gets the same 4096 cap the app gives it.
            cap = 4096 if r["task"] == "EXAM" else a.max_new_tokens
            text, m = be.generate(r["prompt"], cap)
            v = extract(text)
            shape_ok = v is not None and satisfies(v, r["schema"])
            problems = task_checks(r["task"], v) if shape_ok else []
            rec = {"task": r["task"], "id": r["id"], "json": v is not None, "shape": shape_ok,
                   "usable": shape_ok and not problems, "problems": problems, **m, "reply": text}
            results.append(rec)
            f.write(json.dumps(rec, ensure_ascii=False) + "\n")
            print(f"{r['task']:<20} {r['id']:<14} json={rec['json']!s:<5} usable={rec['usable']!s:<5} "
                  f"{m['out_tokens']:>4} tok {m['seconds']:.1f}s {'; '.join(problems)}", flush=True)

    summary = write_summary(out_dir, tag, be, results)
    print("\n" + summary)


def write_summary(out_dir: Path, tag: str, be, results) -> str:
    lines = [f"# hosteval — {be.name}", "",
             "PC GPU(RTX 3070) 대역 평가. **형식·내용 품질**만 본다. S25 속도·발열은 실기에서 잰다.", "",
             f"- 모델 로드 {be.load_s:.1f}s, 최대 VRAM {be.vram_gb():.2f} GB", "",
             "| 작업 | 건수 | JSON 추출 | 스키마 충족 | 앱에서 사용 가능 | 출력 토큰 중앙값 | 지연 중앙값(3070) | tok/s |",
             "|---|---|---|---|---|---|---|---|"]
    for task in dict.fromkeys(r["task"] for r in results):
        rs = [r for r in results if r["task"] == task]
        n = len(rs)
        pct = lambda k: f"{sum(r[k] for r in rs)}/{n}"
        tps = [r["out_tokens"] / r["seconds"] for r in rs if r["seconds"] > 0]
        lines.append(f"| {task} | {n} | {pct('json')} | {pct('shape')} | {pct('usable')} | "
                     f"{statistics.median(r['out_tokens'] for r in rs):.0f} | "
                     f"{statistics.median(r['seconds'] for r in rs):.1f}s | "
                     f"{statistics.median(tps):.0f} |")
    fails = [r for r in results if not r["usable"]]
    if fails:
        lines += ["", "## 사용 불가 (앱이라면 클라우드로 넘어갔을 건)", ""]
        for r in fails:
            why = "; ".join(r["problems"]) or ("JSON 없음" if not r["json"] else "필수 키 누락")
            lines.append(f"- {r['task']} / {r['id']}: {why}")
    text = "\n".join(lines) + "\n"
    (out_dir / f"{tag}.md").write_text(text)
    return text


if __name__ == "__main__":
    main()
