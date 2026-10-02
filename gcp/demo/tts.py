"""Local neural TTS (Kokoro, ONNX): no network, no cloud. Usage: tts.py jobs.json
jobs.json: {"voice": "af_heart", "speed": 1.0, "items": [{"file": "out.wav", "text": "..."}]}
Env: MODELS = directory holding kokoro-v1.0.onnx and voices-v1.0.bin"""
import json, os, sys
import numpy as np, soundfile as sf
from kokoro_onnx import Kokoro

job = json.load(open(sys.argv[1]))
models = os.environ["MODELS"]
k = Kokoro(os.path.join(models, "kokoro-v1.0.onnx"), os.path.join(models, "voices-v1.0.bin"))
for it in job["items"]:
    audio, sr = k.create(it["text"], voice=job.get("voice", "af_heart"), speed=job.get("speed", 1.0), lang="en-us")
    # trim leading/trailing silence, then add a short natural tail
    idx = np.where(np.abs(audio) > 0.01)[0]
    if len(idx):
        audio = audio[max(idx[0] - 800, 0): idx[-1] + 2400]
    sf.write(it["file"], audio, sr)
    print(it["file"], round(len(audio) / sr, 1), "s", flush=True)
