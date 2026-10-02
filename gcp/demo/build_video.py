#!/usr/bin/env python3
"""Builds out/flight-agent-adk-demo.mp4 (+ .srt) from the real command output saved by capture.sh.

    ./capture.sh && uv run --with pillow python build_video.py

Rules (same as the other demo videos): narrate only what is on screen, say what is local / scripted / not done yet,
and say that the voice is synthetic. Narration is Kokoro (offline neural TTS, voice af_heart), run by tts.py:

    MODELS=/dir/with/kokoro-v1.0.onnx+voices-v1.0.bin uv run --with pillow python build_video.py
"""

from __future__ import annotations

import json
import os
import re
import subprocess
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

HERE = Path(__file__).parent
OUT = HERE / "out"
CAP = OUT / "captures"
WORK = OUT / "work"
W, H = 1920, 1080
VOICE, SPEED = "af_heart", 1.0

# Spelled out for the voice only; captions keep the normal spelling
SPOKEN = {"A2A": "A two A", "MCP": "M C P", "OIDC": "O I D C", "BigQuery": "Big Query", "Terraform": "Terra form"}


def spoken(text: str) -> str:
    for written, said in SPOKEN.items():
        text = text.replace(written, said)
    return text


BG, PANEL, FG, DIM = (14, 17, 23), (22, 27, 36), (230, 235, 245), (130, 140, 160)
GREEN, RED, YELLOW, BLUE, ACCENT = (110, 220, 140), (255, 120, 120), (255, 205, 100), (120, 180, 255), (96, 165, 250)
MONO, SANS = "/System/Library/Fonts/Menlo.ttc", "/System/Library/Fonts/Supplemental/Arial.ttf"
SANS_BOLD = "/System/Library/Fonts/Supplemental/Arial Bold.ttf"


def font(path, size):
    return ImageFont.truetype(path, size)


# ---- scenes -------------------------------------------------------------------------------------------------------

SCENES = [
    dict(kind="slide", title="Travel Assistant on Google ADK",
         bullets=["Port of my multi-agent travel system to Google's Agent Development Kit",
                  "Everything in this video runs locally",
                  "Gemini on Vertex AI and the Google Cloud deployment: not done yet (see the last slide)"],
         say="This is a short update on the Google Cloud port of my multi-agent travel system. Everything you will see "
             "is running locally. The Gemini model and the Google Cloud deployment are the next step, and I will say "
             "clearly where that line is."),
    dict(kind="arch",
         say="The orchestrator is Java with Spring A I. It delegates over A2A to a flight agent and a hotel agent, "
             "which use MCP servers for their tools. In this port, the flight agent is rewritten on Google's Agent "
             "Development Kit, in Python, next to the Java original, which stays in the repository. The orchestrator, the MCP servers and the hotel agent are the existing Java "
             "services, unchanged. That is the point of A2A and MCP: the protocols stay stable while the framework changes."),
    dict(kind="term", file="1_status.txt", title="Java orchestrator  →  agent cards",
         say="Here the Java orchestrator asks for the status of its agents. It reads each agent's card with the Java A2A "
             "SDK. The flight agent is the new Python service. It is available, speaks protocol zero point three, and "
             "advertises two skills: flight search, and a policy check. In deployment that check reads BigQuery; "
             "locally it reads the same data from C S V files."),
    dict(kind="term", file="2_card.txt", title="Flight agent card (Python / ADK)",
         say="The card declares a bearer security scheme, with the same name the Java agents use, so the existing "
             "orchestrator authenticates against the new agent without any change."),
    dict(kind="term", file="3_auth.txt", title="Service auth and guardrails over A2A",
         say="Now auth and guardrails. No token: four hundred and one. Wrong token: four hundred and one. With the right "
             "token, I send a prompt injection over A2A. The guardrail blocks it before any model is called, and the "
             "task completes with a clear error. No model was involved in that answer."),
    dict(kind="term", file="4_tests.txt", title="Tests",
         say="The integration tests run the real agent loop against the live Java MCP server, with a scripted stand-in "
             "for the model, so there is no model cost. They check that a search goes through authenticated MCP and "
             "arrives wrapped as untrusted data, with the booking tool hidden from the model. That an injection never "
             "reaches the model or the MCP server. And that with a wrong service token the agent fails closed, instead "
             "of improvising answers. The whole suite is thirty eight tests."),
    dict(kind="term", file="5_terraform.txt", title="Infrastructure as code",
         say="The infrastructure is Terraform: a Cloud Run service with its own service account, a BigQuery dataset "
             "where the agent has read only access, and invoker bindings so that only the orchestrator can call the "
             "agent. It validates, but I have not applied it, because that needs a Google Cloud project."),
    dict(kind="slide", title="What is done, and what is next",
         bullets=["Done, running locally: ADK flight agent, A2A + MCP interop with the Java services, guardrails, auth, 38 tests, Terraform (validated)",
                  "Not done yet: Gemini on Vertex AI, deploy to Cloud Run / Agent Engine, load BigQuery tables, OIDC auth on real Cloud Run",
                  "Needs a Google Cloud project, which is the next step",
                  "The narration voice is synthetic"],
         say="What is not done yet: running with Gemini on Vertex A I, deploying to Cloud Run and then Agent Engine, "
             "loading the BigQuery tables, and testing the OIDC authentication on real Cloud Run. That needs a Google "
             "Cloud project, and it is the next step. The narration voice in this video is synthetic."),
]

# ---- rendering ----------------------------------------------------------------------------------------------------


def wrap(draw, text, fnt, max_w):
    words, lines, cur = text.split(), [], ""
    for word in words:
        trial = f"{cur} {word}".strip()
        if draw.textlength(trial, font=fnt) <= max_w:
            cur = trial
        else:
            lines.append(cur)
            cur = word
    return lines + [cur]


def slide(title, bullets):
    img = Image.new("RGB", (W, H), BG)
    d = ImageDraw.Draw(img)
    d.rectangle([0, 0, 14, H], fill=ACCENT)
    d.text((120, 130), title, font=font(SANS_BOLD, 76), fill=FG)
    y, f = 330, font(SANS, 42)
    for bullet in bullets:
        for i, line in enumerate(wrap(d, bullet, f, W - 300)):
            if i == 0:
                d.ellipse([120, y + 17, 138, y + 35], fill=ACCENT)
            d.text((170, y), line, font=f, fill=FG if "Not done" not in bullet else YELLOW)
            y += 58
        y += 26
    return img


def box(d, xy, label, sub, color, extra=None):
    d.rounded_rectangle(xy, 18, fill=PANEL, outline=color, width=3)
    d.text((xy[0] + 28, xy[1] + 22), label, font=font(SANS_BOLD, 38), fill=FG)
    d.text((xy[0] + 28, xy[1] + 78), sub, font=font(SANS, 28), fill=DIM)
    if extra:
        d.text((xy[0] + 28, xy[1] + 116), extra, font=font(SANS, 26), fill=color)


def arrow(d, a, b, label, color=DIM):
    d.line([a, b], fill=color, width=4)
    d.polygon([(b[0], b[1]), (b[0] - 18 * (1 if b[0] >= a[0] else -1) * (b[1] == a[1]), b[1] - 10 * (b[1] != a[1]) - 10 * (b[1] == a[1])),
               (b[0] - 18 * (1 if b[0] >= a[0] else -1) * (b[1] == a[1]), b[1] + 10)] if b[1] == a[1] else
              [(b[0], b[1]), (b[0] - 10, b[1] - 18), (b[0] + 10, b[1] - 18)], fill=color)
    d.text(((a[0] + b[0]) // 2 + 14, (a[1] + b[1]) // 2 - 16), label, font=font(SANS, 26), fill=color)


def architecture():
    img = Image.new("RGB", (W, H), BG)
    d = ImageDraw.Draw(img)
    d.text((120, 80), "Same system, new framework for one agent", font=font(SANS_BOLD, 60), fill=FG)
    box(d, (660, 220, 1260, 360), "Orchestrator", "Java · Spring AI · unchanged", DIM)
    box(d, (200, 520, 900, 680), "Flight Agent", "Python · Google ADK · NEW (Java original kept)", GREEN, "+ policy & fare history (BigQuery; CSV locally)")
    box(d, (1020, 520, 1720, 660), "Hotel Agent", "Java · Spring AI · unchanged", DIM)
    box(d, (200, 800, 900, 940), "Flight MCP server", "Java · unchanged", DIM)
    box(d, (1020, 800, 1720, 940), "Hotel MCP server", "Java · unchanged", DIM)
    arrow(d, (800, 360), (550, 520), "A2A", GREEN)
    arrow(d, (1120, 360), (1370, 520), "A2A")
    arrow(d, (550, 680), (550, 800), "MCP", GREEN)
    arrow(d, (1370, 660), (1370, 800), "MCP")
    return img


def term_colour(line):
    if line.startswith("$"):
        return GREEN
    if re.search(r"HTTP 40\d|FAILED|Error", line):
        return RED
    if re.search(r"PASSED|passed|Success", line):
        return GREEN
    if "PROMPT_INJECTION" in line or line.startswith("answer"):
        return YELLOW
    if re.match(r'\s*"(name|available|url|securitySchemes)"', line):
        return BLUE
    return FG


def terminal(title, lines, visible):
    avail = H - 200
    size = max(20, min(34, int(avail / (max(len(lines), 1) * 1.38))))
    fnt = font(MONO, size)
    lh = int(size * 1.38)
    img = Image.new("RGB", (W, H), BG)
    d = ImageDraw.Draw(img)
    d.rounded_rectangle([90, 60, W - 90, H - 60], 22, fill=PANEL)
    for i, c in enumerate([(255, 95, 86), (255, 189, 46), (39, 201, 63)]):
        d.ellipse([130 + i * 44, 92, 156 + i * 44, 118], fill=c)
    d.text((W // 2 - d.textlength(title, font=font(SANS, 28)) // 2, 90), title, font=font(SANS, 28), fill=DIM)
    y = 160
    for line in lines[:visible]:
        d.text((140, y), line, font=fnt, fill=term_colour(line))
        y += lh
    return img


# ---- audio / video ------------------------------------------------------------------------------------------------


def run(*cmd):
    subprocess.run([str(c) for c in cmd], check=True, capture_output=True)


def duration(path) -> float:
    out = subprocess.run(["ffprobe", "-v", "error", "-show_entries", "format=duration", "-of", "csv=p=0", str(path)],
                         check=True, capture_output=True, text=True).stdout
    return float(out.strip())


def srt_time(t: float) -> str:
    ms = int(round(t * 1000))
    return f"{ms // 3600000:02}:{ms // 60000 % 60:02}:{ms // 1000 % 60:02},{ms % 1000:03}"


def main():
    WORK.mkdir(parents=True, exist_ok=True)
    models = os.environ.get("MODELS")
    if not models:
        raise SystemExit("Set MODELS to the directory holding kokoro-v1.0.onnx and voices-v1.0.bin")
    jobs = {"voice": VOICE, "speed": SPEED,
            "items": [{"file": str(WORK / f"k{n}.wav"), "text": spoken(sc["say"])} for n, sc in enumerate(SCENES)]}
    (WORK / "jobs.json").write_text(json.dumps(jobs))
    subprocess.run(["uv", "run", "--python", "3.12", "--with", "kokoro-onnx", "--with", "soundfile", "--with", "numpy",
                    "python", str(HERE / "tts.py"), str(WORK / "jobs.json")], check=True, env={**os.environ, "MODELS": models})

    clips, captions, clock = [], [], 0.0
    for n, scene in enumerate(SCENES):
        wav = WORK / f"s{n}.wav"
        run("ffmpeg", "-y", "-i", WORK / f"k{n}.wav", "-ar", 44100, "-ac", 1, wav)
        talk = duration(wav)
        total = talk + 1.2

        frames = []  # (png, seconds)
        if scene["kind"] == "slide":
            img = slide(scene["title"], scene["bullets"])
            frames = [(img, total)]
        elif scene["kind"] == "arch":
            frames = [(architecture(), total)]
        else:
            lines = (CAP / scene["file"]).read_text().rstrip("\n").split("\n")
            reveal = talk * 0.55  # output fills in while the narrator talks, then holds
            per = reveal / max(len(lines) - 1, 1)
            for i in range(1, len(lines) + 1):
                frames.append((terminal(scene["title"], lines, i), 0.4 if i == 1 else per))
            frames[-1] = (frames[-1][0], total - sum(s for _, s in frames[:-1]))

        listing = []
        for i, (img, secs) in enumerate(frames):
            png = WORK / f"s{n}_{i:03}.png"
            img.save(png)
            listing += [f"file '{png.name}'", f"duration {secs:.3f}"]
        listing.append(f"file '{png.name}'")  # concat demuxer needs the last file repeated
        (WORK / f"s{n}.txt").write_text("\n".join(listing) + "\n")

        clip = WORK / f"clip{n}.mp4"
        run("ffmpeg", "-y", "-f", "concat", "-safe", 0, "-i", WORK / f"s{n}.txt", "-i", wav,
            "-vf", "fps=30,format=yuv420p", "-af", "apad", "-t", f"{total:.3f}",
            "-c:v", "libx264", "-preset", "medium", "-crf", 20, "-c:a", "aac", "-b:a", "160k", clip)
        clips.append(clip)

        # captions: one per sentence, timed by share of characters
        sentences = [s.strip() for s in re.split(r"(?<=[.:])\s+", scene["say"]) if s.strip()]
        chars = sum(len(s) for s in sentences)
        t = clock
        for s in sentences:
            span = talk * len(s) / chars
            captions.append((t, t + span, s))
            t += span
        clock += total

    (WORK / "clips.txt").write_text("".join(f"file '{c.name}'\n" for c in clips))
    final = OUT / "flight-agent-adk-demo.mp4"
    run("ffmpeg", "-y", "-f", "concat", "-safe", 0, "-i", WORK / "clips.txt", "-c", "copy", "-movflags", "+faststart", final)
    (OUT / "flight-agent-adk-demo.srt").write_text("".join(
        f"{i}\n{srt_time(a)} --> {srt_time(b)}\n{text}\n\n" for i, (a, b, text) in enumerate(captions, 1)))
    print(f"{final}  {duration(final):.0f}s  {final.stat().st_size / 1e6:.1f} MB")


if __name__ == "__main__":
    main()
