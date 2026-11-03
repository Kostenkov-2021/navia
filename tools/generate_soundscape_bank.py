"""Generates the ambient sound bank behind the map's "Simulate the sound here" action (see
classes/soundscape/SoundscapePlayer) with Stable Audio Open 1.0 on Replicate.

Every clip is a MONO loop (or a short one-shot) — the app positions each one in 3D itself with
OpenAL Soft's HRTF renderer, so nothing here may carry stereo imaging, a pass-by envelope or a
Doppler sweep of its own. Vehicles in particular are generated as a steady engine/rolling noise at
constant distance; the app moves the source along the street or track and OpenAL adds the Doppler.

Idempotent and cheap to rerun: each raw generation is cached in tools/soundscape_raw/ under a name
derived from its prompt + seed, so editing one entry only regenerates that entry. Every Replicate
call costs real money — do not delete the cache casually.

Needs the REPLICATE_API_TOKEN environment variable (never copied into the app) and ffmpeg on PATH.

    python tools/generate_soundscape_bank.py            # generate missing + re-encode all
    python tools/generate_soundscape_bank.py --only car  # just one entry

Outputs, per clip:
  * tools/soundscape_master/<id>.wav — the processed, loop-ready master (16-bit PCM mono, 44.1 kHz,
    committed). This is the source of truth: any shipped format is derived from it, so the shipped
    codec can change later without regenerating (or paying for) anything.
  * app/src/main/assets/soundscape/<id>.ogg — what the app ships, encoded from the master. Ogg
    Vorbis rather than AAC/m4a on purpose: AAC adds encoder-priming/padding samples that Android's
    MediaCodec does not reliably trim, which puts a gap or click at every loop point; Vorbis is
    sample-exact, so a crossfaded loop stays seamless.
  * app/src/main/assets/soundscape/bank.json (id -> kind/duration/prompt/seed), which the app reads
    to know what exists.
"""
import argparse
import hashlib
import json
import os
import subprocess
import sys
import time
import urllib.error
import urllib.request

import numpy as np
import soundfile as sf

MODEL_VERSION = "9aff84a639f96d0f7e6081cdea002d15133d0043727f849c40abdd166b7c75a8"  # stackadoc/stable-audio-open-1.0
HERE = os.path.dirname(os.path.abspath(__file__))
RAW_DIR = os.path.join(HERE, "soundscape_raw")
MASTER_DIR = os.path.join(HERE, "soundscape_master")
OUT_DIR = os.path.join(HERE, "..", "app", "src", "main", "assets", "soundscape")
SAMPLE_RATE = 44100

NEGATIVE = "music, melody, instruments, singing, speech, voice over, narration, low quality, distortion, clipping"

# kind: "loop" clips get a crossfaded loop point; "oneshot" clips are faded in/out and played now
# and then. seconds is what is generated; loops lose LOOP_XFADE_S of it to the crossfade.
BANK = [
    # --- moving sources (steady, no pass-by) ---
    ("car", "loop", 14, "field recording, steady sound of a single car engine and tires rolling on asphalt at constant speed, recorded close, no pass-by, continuous"),
    ("truck", "loop", 14, "field recording, steady low rumble of a heavy diesel truck engine and tires rolling at constant speed, continuous, recorded close"),
    ("bus", "loop", 14, "field recording, steady city bus diesel engine driving at constant low speed, continuous hum, recorded close"),
    ("motorcycle", "loop", 12, "field recording, steady motorcycle engine cruising at constant speed, continuous, recorded close"),
    ("bicycle", "loop", 10, "field recording, bicycle rolling on pavement, freewheel ticking and chain, steady, close, quiet"),
    ("train", "loop", 16, "field recording, steady rhythmic train wheels clacking over rail joints and rumbling, continuous, constant speed, recorded close to the track"),
    ("tram", "loop", 16, "field recording, steady electric tram rolling on rails, motor whine and wheel squeal, continuous, constant speed, recorded close"),
    # --- static / ambience loops ---
    ("traffic_far", "loop", 20, "field recording, distant busy city traffic, steady broadband hum of many cars, far away, continuous ambience"),
    ("city_hum", "loop", 20, "field recording, quiet urban background ambience, distant city murmur, soft and continuous, no single events"),
    ("birds", "loop", 20, "field recording, many small songbirds chirping in trees in a city park, daytime, natural ambience"),
    ("leaves", "loop", 20, "field recording, gentle wind rustling leaves in tall trees, soft continuous ambience"),
    ("playground", "loop", 20, "field recording, children playing and laughing on a playground, swings creaking, outdoors, distant cheerful ambience"),
    ("schoolyard", "loop", 20, "field recording, school yard during break time, many children talking and shouting, ball bouncing, outdoors"),
    ("cafe", "loop", 20, "field recording, busy cafe terrace, people chatting softly, cups and saucers clinking, espresso machine, ambience"),
    ("restaurant", "loop", 20, "field recording, restaurant ambience, cutlery and plates clinking, murmur of diners talking, indistinct"),
    ("pub", "loop", 20, "field recording, lively pub crowd talking and laughing, glasses clinking, indistinct chatter"),
    ("market", "loop", 20, "field recording, busy shopping street and market, crowd murmur, footsteps, shop doors, indistinct voices"),
    ("footsteps", "loop", 16, "field recording, pedestrian street, many people walking past, footsteps on pavement, soft indistinct murmur"),
    ("fountain", "loop", 16, "field recording, water fountain splashing in a public square, continuous water"),
    ("ped_signal", "loop", 8, "field recording, accessible pedestrian crossing signal ticking steadily, repetitive tick tick at traffic light, outdoors"),
    ("crossing_bell", "loop", 8, "field recording, railway level crossing warning bell ringing continuously, ding ding ding"),
    ("sports", "loop", 20, "field recording, people playing football on an outdoor sports field, ball kicks, shouts, whistle in the distance"),
    ("construction", "loop", 16, "field recording, distant construction site, hammering, machinery, metallic clanks, outdoors"),
    # --- one-shots, played occasionally ---
    ("car_horn", "oneshot", 4, "field recording, a single short car horn honk in a city street"),
    ("tram_bell", "oneshot", 4, "field recording, a tram warning bell ringing twice, ding ding, outdoors"),
    ("bus_doors", "oneshot", 6, "field recording, city bus stopping, air brakes hiss and doors opening with a pneumatic hiss"),
    ("church_bells", "oneshot", 12, "field recording, church bells ringing from a bell tower, heard from the street"),
    ("dog_bark", "oneshot", 4, "field recording, a dog barking a few times in a residential street, outdoors"),
    ("siren", "oneshot", 10, "field recording, distant ambulance siren passing in the city, far away"),
    ("bicycle_bell", "oneshot", 3, "field recording, a single bicycle bell ring, outdoors"),
    ("pigeons", "oneshot", 6, "field recording, pigeons cooing and flapping wings on a city square"),
]

LOOP_XFADE_S = 1.5
ONESHOT_FADE_S = 0.05
TARGET_RMS_DBFS = -20.0
PEAK_LIMIT = 0.89


def raw_path(clip_id, prompt, seconds, seed):
    h = hashlib.sha1(f"{prompt}|{seconds}|{seed}|{MODEL_VERSION}".encode()).hexdigest()[:10]
    return os.path.join(RAW_DIR, f"{clip_id}_{h}.wav")


def replicate(prompt, seconds, seed, token):
    body = json.dumps({"version": MODEL_VERSION, "input": {
        "prompt": prompt, "negative_prompt": NEGATIVE, "seconds_total": seconds,
        "seed": seed, "steps": 100, "cfg_scale": 7}}).encode()
    req = urllib.request.Request("https://api.replicate.com/v1/predictions", data=body, headers={
        "Authorization": f"Bearer {token}", "Content-Type": "application/json", "Prefer": "wait=60"})
    for attempt in range(8):
        try:
            pred = json.load(urllib.request.urlopen(req, timeout=120))
            break
        except urllib.error.HTTPError as e:
            # Replicate throttles prediction creation hard on low-credit accounts (429); back off.
            if e.code != 429 or attempt == 7:
                raise
            wait = int(e.headers.get("Retry-After") or 0) or 10 * (attempt + 1)
            print(f"  rate limited, retrying in {wait}s", flush=True)
            time.sleep(wait)
    while pred["status"] in ("starting", "processing"):
        time.sleep(3)
        req = urllib.request.Request(pred["urls"]["get"], headers={"Authorization": f"Bearer {token}"})
        pred = json.load(urllib.request.urlopen(req, timeout=60))
    if pred["status"] != "succeeded":
        raise RuntimeError(f"prediction {pred['id']} {pred['status']}: {pred.get('error')}")
    out = pred["output"]
    url = out[0] if isinstance(out, list) else out
    with urllib.request.urlopen(url, timeout=120) as r:
        return r.read(), url


def decode_any(data, dest_wav):
    """The model's output format is whatever the Replicate wrapper chose (wav/mp3); normalize to a
    float wav via ffmpeg so the rest of the pipeline only ever sees one format."""
    tmp = dest_wav + ".download"
    with open(tmp, "wb") as f:
        f.write(data)
    subprocess.run(["ffmpeg", "-y", "-loglevel", "error", "-i", tmp, "-ar", str(SAMPLE_RATE),
                    "-c:a", "pcm_f32le", dest_wav], check=True)
    os.remove(tmp)


def to_mono(x):
    return x.mean(axis=1) if x.ndim == 2 else x


def trim_silence(x, thresh_db=-50.0):
    """Stable Audio pads short prompts with near-silence at the end; drop it."""
    win = SAMPLE_RATE // 20
    n = len(x) // win
    if n == 0:
        return x
    rms = np.sqrt(np.mean(x[:n * win].reshape(n, win) ** 2, axis=1) + 1e-12)
    loud = np.where(20 * np.log10(rms) > thresh_db)[0]
    if len(loud) == 0:
        return x
    return x[loud[0] * win:(loud[-1] + 1) * win]


def make_loop(x):
    """Equal-power crossfade of the tail into the head, so the clip repeats without a click."""
    xf = int(LOOP_XFADE_S * SAMPLE_RATE)
    if len(x) < 3 * xf:
        xf = len(x) // 3
    t = np.linspace(0, np.pi / 2, xf)
    head = x[:xf] * np.sin(t)
    tail = x[-xf:] * np.cos(t)
    body = x[xf:-xf].copy()
    return np.concatenate([body, head + tail]) if len(body) else head + tail


def normalize(x):
    rms = np.sqrt(np.mean(x ** 2) + 1e-12)
    x = x * (10 ** (TARGET_RMS_DBFS / 20) / rms)
    peak = np.max(np.abs(x))
    if peak > PEAK_LIMIT:
        x = x * (PEAK_LIMIT / peak)
    return x


def highpass(x, hz=40.0):
    # One-pole DC/rumble blocker — model output sometimes has a DC offset that thumps when looped.
    a = np.exp(-2 * np.pi * hz / SAMPLE_RATE)
    y = np.empty_like(x)
    prev_x = prev_y = 0.0
    for i, v in enumerate(x):
        prev_y = a * (prev_y + v - prev_x)
        prev_x = v
        y[i] = prev_y
    return y


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--only", help="comma-separated clip ids")
    ap.add_argument("--seed", type=int, default=1234)
    args = ap.parse_args()
    token = os.environ.get("REPLICATE_API_TOKEN")
    os.makedirs(RAW_DIR, exist_ok=True)
    os.makedirs(MASTER_DIR, exist_ok=True)
    os.makedirs(OUT_DIR, exist_ok=True)
    only = set(args.only.split(",")) if args.only else None

    manifest = {}
    for clip_id, kind, seconds, prompt in BANK:
        rp = raw_path(clip_id, prompt, seconds, args.seed)
        if not os.path.exists(rp) and (only is None or clip_id in only):
            if not token:
                sys.exit("REPLICATE_API_TOKEN is not set")
            print(f"generating {clip_id} ({seconds}s)...", flush=True)
            data, url = replicate(prompt, seconds, args.seed, token)
            decode_any(data, rp)
        if not os.path.exists(rp):
            print(f"skip {clip_id}: not generated yet")
            continue

        x, sr = sf.read(rp, dtype="float64")
        assert sr == SAMPLE_RATE, sr
        x = highpass(trim_silence(to_mono(x)))
        if kind == "loop":
            x = make_loop(x)
        else:
            f = int(ONESHOT_FADE_S * SAMPLE_RATE)
            x[:f] *= np.linspace(0, 1, f)
            x[-f * 4:] *= np.linspace(1, 0, f * 4)
        x = normalize(x)

        master = os.path.join(MASTER_DIR, f"{clip_id}.wav")
        sf.write(master, x.astype(np.float32), SAMPLE_RATE, subtype="PCM_16")
        out = os.path.join(OUT_DIR, f"{clip_id}.ogg")
        subprocess.run(["ffmpeg", "-y", "-loglevel", "error", "-i", master, "-ac", "1",
                        "-c:a", "libvorbis", "-q:a", "3", out], check=True)
        manifest[clip_id] = {"kind": kind, "seconds": round(len(x) / SAMPLE_RATE, 2),
                             "prompt": prompt, "seed": args.seed}
        print(f"{clip_id}: {manifest[clip_id]['seconds']}s -> {os.path.getsize(out) // 1024} KB")

    with open(os.path.join(OUT_DIR, "bank.json"), "w", encoding="utf-8") as f:
        json.dump({"model": "stability-ai/stable-audio-open-1.0", "replicate_version": MODEL_VERSION,
                   "sample_rate": SAMPLE_RATE, "clips": manifest}, f, indent=1)


if __name__ == "__main__":
    main()
