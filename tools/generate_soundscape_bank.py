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
# and then; "steady" clips are loops for moving sources (see STEADY_SEEDS). seconds is what is
# generated; loops lose LOOP_XFADE_S of it to the crossfade.
BANK = [
    # --- moving sources (steady, no pass-by) ---
    ("car", "steady", 20, "field recording, steady uniform sound of one car engine and tires rolling on asphalt at constant speed, constant distance, constant loudness, recorded from inside the wheel arch, monotone continuous drone, nothing changes"),
    ("truck", "loop", 14, "field recording, steady low rumble of a heavy diesel truck engine and tires rolling at constant speed, continuous, recorded close"),
    ("bus", "loop", 14, "field recording, steady city bus diesel engine driving at constant low speed, continuous hum, recorded close"),
    ("motorcycle", "steady", 20, "field recording, steady uniform motorcycle engine drone at constant speed and constant rpm, constant distance, constant loudness, monotone, continuous, nothing changes"),
    ("bicycle", "steady", 20, "field recording, steady uniform sound of bicycle tires rolling on pavement with a soft freewheel ticking and chain, constant speed, constant distance, constant loudness, continuous, close"),
    ("train", "loop", 16, "field recording, steady rhythmic train wheels clacking over rail joints and rumbling, continuous, constant speed, recorded close to the track"),
    ("tram", "steady", 20, "field recording, steady uniform electric tram motor hum and wheels rolling on rails, constant speed, constant distance, constant loudness, monotone continuous, recorded on board"),
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
    # --- added after surveying 45 planet boxes for what the map data commonly holds ---
    # nature: water
    ("waterfall", "loop", 20, "field recording, powerful waterfall, roaring cascading water crashing onto rocks, continuous, close"),
    ("river", "loop", 20, "field recording, wide river flowing steadily, water rushing over stones, continuous, natural ambience"),
    ("stream", "loop", 20, "field recording, small mountain brook babbling over pebbles, gentle trickling water, continuous"),
    ("sea_waves", "loop", 20, "field recording, ocean waves gently breaking on a sandy beach, rolling surf, continuous, natural"),
    ("rocky_shore", "loop", 20, "field recording, sea waves crashing against rocky cliffs, splashing surf, continuous, windy coast"),
    ("harbor", "loop", 20, "field recording, quiet harbor, water lapping against boats and quay, rigging clinking, wooden boats creaking"),
    ("seagulls", "oneshot", 8, "field recording, seagulls calling over a harbor, several gull cries, outdoors"),
    ("ship_horn", "oneshot", 8, "field recording, a ferry ship horn blowing a long deep blast in a harbor, distant"),
    ("cave_drips", "loop", 16, "field recording, inside a cave entrance, water droplets dripping into puddles, echoing, quiet"),
    # nature: land
    ("forest", "loop", 20, "field recording, deep forest ambience, distant woodpecker, forest birds, soft wind in tall trees, no traffic"),
    ("wind_open", "loop", 20, "field recording, steady wind blowing over an open mountain ridge, gusting, whistling, continuous"),
    ("frogs", "loop", 20, "field recording, frogs croaking in a pond and wetland, many frogs, evening, natural ambience"),
    ("crickets", "loop", 20, "field recording, crickets and grasshoppers chirping in a summer meadow, continuous insect ambience"),
    ("countryside", "loop", 20, "field recording, quiet countryside ambience, distant birds, faint far away tractor, gentle breeze, peaceful"),
    ("rooster", "oneshot", 5, "field recording, a rooster crowing on a farm, outdoors"),
    ("cows", "oneshot", 8, "field recording, cows mooing in a pasture, cowbells, outdoors"),
    ("sheep", "oneshot", 8, "field recording, a flock of sheep bleating in a meadow, outdoors"),
    ("goats", "oneshot", 8, "field recording, goats bleating on a farm, small bells, outdoors"),
    ("horses", "oneshot", 8, "field recording, horses neighing and snorting at a riding stable, hooves on ground, outdoors"),
    ("kennel_dogs", "oneshot", 8, "field recording, several dogs barking at an animal shelter kennel, outdoors, distant"),
    ("campfire", "loop", 16, "field recording, campfire crackling and popping, burning wood, outdoors, close"),
    # places
    ("fuel_station", "loop", 20, "field recording, petrol station forecourt, car idling, fuel pump running, car door closing, indistinct"),
    ("car_wash", "loop", 16, "field recording, self service car wash, high pressure water jet spraying a car, continuous"),
    ("garage", "loop", 16, "field recording, car repair workshop, pneumatic impact wrench, air compressor, metal tools clanking"),
    ("supermarket", "loop", 20, "field recording, supermarket entrance, automatic doors, shopping carts rattling, checkout scanner beeps, crowd murmur"),
    ("shop_bell", "oneshot", 3, "field recording, a small shop door opening with a bell chime ringing"),
    ("station_hall", "loop", 20, "field recording, busy train station hall, rolling suitcases, footsteps, echoing crowd, distant chime, indistinct"),
    ("nightclub", "loop", 16, "field recording, muffled bass thumping through the walls of a nightclub, heard from the street outside"),
    ("temple_bell", "oneshot", 10, "field recording, a large temple bell struck once, deep resonant gong, long decay, outdoors"),
    ("car_gravel", "steady", 20, "field recording, steady uniform tires crunching on a gravel road at constant speed, constant distance, constant loudness, continuous, recorded close to the wheel"),
    # --- variety pass (see docs/soundscape-rules.md): more vehicles, street life and kinds of place ---
    ("car_b", "steady", 20, "field recording, steady uniform sound of a small petrol car engine and tires on asphalt at constant speed, constant distance, constant loudness, monotone continuous drone, nothing changes"),
    ("car_slow", "steady", 20, "field recording, steady uniform quiet low-speed car engine purr and soft tires rolling slowly on a residential street, constant speed, constant distance, constant loudness, continuous"),
    ("car_fast", "steady", 20, "field recording, steady uniform loud tire roar and wind noise of a car at high motorway speed, constant speed, constant distance, constant loudness, broadband continuous drone, nothing changes"),
    ("bicycle_b", "steady", 20, "field recording, steady uniform sound of a city bicycle rolling, soft tire hum, light chain rattle, constant speed, constant distance, constant loudness, continuous, close"),
    ("airplane", "steady", 20, "field recording, steady uniform jet airliner engine roar high in the sky, constant distance, constant loudness, broadband continuous drone, nothing changes"),
    ("air_noise", "loop", 20, "soft steady white noise, faint air hiss, gentle outdoor room tone, perfectly uniform and continuous, no events"),
    ("river_roar", "loop", 20, "field recording, roaring wide river rushing under a large bridge, powerful continuous water, heard from the bridge, no events"),
    ("ped_beep", "loop", 8, "field recording, accessible pedestrian traffic light beeping steadily, repetitive electronic beep beep beep at a street crossing, outdoors"),
    ("footsteps_dirt", "oneshot", 8, "field recording, one person walking on a dirt path, footsteps crunching and rustling on soil and small stones, outdoors, close"),
    ("children", "oneshot", 7, "field recording, a few young children laughing and calling while playing in a quiet residential street, outdoors, indistinct"),
    ("car_horn_double", "oneshot", 4, "field recording, a car horn honking twice in quick succession in a city street, beep beep, outdoors"),
    ("truck_horn", "oneshot", 5, "field recording, a single deep loud truck air horn blast on a wide city road, outdoors"),
    ("hairdresser", "loop", 16, "field recording, hair salon interior heard from the doorway, hair dryer blowing, scissors snipping, soft indistinct chatter"),
    ("bakery", "loop", 16, "field recording, small bakery shop, paper bags rustling, bread trays sliding, cash register, soft indistinct customers"),
    ("butcher", "oneshot", 6, "field recording, butcher shop, a cleaver chopping on a wooden block several times, indoors heard from the door"),
    ("greengrocer", "loop", 18, "field recording, fruit and vegetable stall on the street, crates being stacked, paper bags, scale clinking, indistinct customers"),
    ("laundry", "loop", 16, "field recording, laundromat, washing machines tumbling and humming, steady, indoors heard from the doorway"),
    ("sewing", "loop", 12, "field recording, tailor shop, electric sewing machine running in short bursts, fabric rustling, quiet room"),
    ("workshop", "loop", 16, "field recording, small craft workshop, electric drill, hand saw on wood, hammer taps, heard from the street"),
    ("bicycle_repair", "oneshot", 6, "field recording, bicycle repair shop, ratchet wrench clicking, wheel spinning freely, tire pump hiss"),
    ("atm", "oneshot", 5, "field recording, a street cash machine, keypad button beeps, banknotes being dispensed, outdoors"),
    ("recycling_glass", "oneshot", 5, "field recording, glass bottles dropped into a street bottle bank, shattering clinks, outdoors"),
    ("vending", "oneshot", 4, "field recording, a street vending machine, coins dropping and a can falling into the tray, outdoors"),
    ("car_door", "oneshot", 5, "field recording, parking lot, a car door closing with a solid thud, then the engine starting, outdoors"),
    ("gym", "loop", 16, "field recording, fitness gym heard from the door, weights clanking, treadmill running, indistinct"),
    ("swimming", "loop", 18, "field recording, public swimming pool, water splashing, swimmers, children shouting, echoing hall, whistle far away"),
    ("cinema_lobby", "loop", 16, "field recording, cinema and theatre foyer, large indoor crowd murmur, popcorn machine, indistinct, echoing"),
    ("hotel", "oneshot", 7, "field recording, hotel entrance, a suitcase with wheels rolling on pavement, automatic door sliding open, outdoors"),
    ("university", "loop", 18, "field recording, university campus courtyard, many students walking and talking, indistinct voices, outdoors"),
    ("theme_park", "loop", 18, "field recording, amusement park from a distance, people screaming on a roller coaster, crowd, mechanical rides, outdoors"),
    ("zoo", "oneshot", 8, "field recording, zoo, exotic birds calling and monkeys screeching in the distance, outdoors"),
    ("arcade", "loop", 14, "field recording, arcade game hall, many electronic game machines beeping and bleeping, indistinct, indoors"),
    ("stadium", "loop", 18, "field recording, football stadium heard from outside, big crowd roaring and chanting in the distance, outdoors"),
    ("clothes_shop", "oneshot", 5, "field recording, clothing shop, metal hangers sliding along a rail, fabric rustling, quiet indoors"),
    ("electronics_shop", "oneshot", 4, "field recording, electronics shop, soft digital beeps from a barcode scanner and devices, quiet indoors"),
    ("kiosk", "oneshot", 4, "field recording, a street kiosk, coins placed on a counter, newspaper rustling, outdoors"),
    ("pet_shop", "oneshot", 6, "field recording, pet shop, small birds chirping and a puppy yapping, indoors"),
]

LOOP_XFADE_S = 1.5
ONESHOT_FADE_S = 0.05
# "steady" = a moving source: it must hold still in pitch and loudness, because the app's renderer adds
# the movement and Doppler. Stable Audio often drifts or does a pass-by despite the prompt (the first
# car clip swung 26 dB), so several seeds are tried and the steadiest stretch of the best one is kept.
STEADY_SEEDS = (1234, 2345, 3456)
STEADY_MIN_S = 7.0
HISS_PENALTY = 40.0
STEADY_NEGATIVE = ", pass-by, passing, approaching, receding, doppler, acceleration, gear change, fade in, fade out, horn"
TARGET_RMS_DBFS = -20.0
PEAK_LIMIT = 0.89


def raw_path(clip_id, prompt, seconds, seed):
    h = hashlib.sha1(f"{prompt}|{seconds}|{seed}|{MODEL_VERSION}".encode()).hexdigest()[:10]
    return os.path.join(RAW_DIR, f"{clip_id}_{h}.wav")


def replicate(prompt, seconds, seed, token, negative=NEGATIVE):
    body = json.dumps({"version": MODEL_VERSION, "input": {
        "prompt": prompt, "negative_prompt": negative, "seconds_total": seconds,
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


def frames(x, win):
    """Per-window loudness (dB) and spectral centroid (Hz)."""
    n = len(x) // win
    rms, cen = np.zeros(n), np.zeros(n)
    f = np.fft.rfftfreq(win, 1 / SAMPLE_RATE)
    w = np.hanning(win)
    for i in range(n):
        s = x[i * win:(i + 1) * win]
        rms[i] = 20 * np.log10(np.sqrt(np.mean(s ** 2)) + 1e-9)
        spec = np.abs(np.fft.rfft(s * w))
        cen[i] = (spec * f).sum() / (spec.sum() + 1e-9)
    return rms, cen


def steadiest(x):
    """The stretch of STEADY_MIN_S that changes least, and its score (lower is steadier): loudness
    swing in dB plus centroid spread as a percentage of its mean."""
    win = SAMPLE_RATE // 4
    rms, cen = frames(x, win)
    size = min(len(rms), int(STEADY_MIN_S * 4))
    if size == 0:
        return x, 1e9
    best, best_score = 0, 1e9
    for i in range(len(rms) - size + 1):
        r, c = rms[i:i + size], cen[i:i + size]
        score = (r.max() - r.min()) + 100 * c.std() / (c.mean() + 1e-9)
        if score < best_score:
            best, best_score = i, score
    part = x[best * win:(best + size) * win]
    # Plain hiss is the steadiest sound of all, but it is no engine: a take heavy above 4 kHz loses.
    power = np.abs(np.fft.rfft(part)) ** 2
    hiss = power[np.fft.rfftfreq(len(part), 1 / SAMPLE_RATE) >= 4000].sum() / (power.sum() + 1e-12)
    return part, best_score + HISS_PENALTY * hiss


def flatten_loudness(x):
    """Evens out what slow loudness drift remains (1 s window, at most +-9 dB); fast texture stays."""
    win = SAMPLE_RATE
    kernel = np.ones(win) / win
    env = np.sqrt(np.convolve(x ** 2, kernel, mode="same") + 1e-9)
    gain = np.clip(np.sqrt(np.mean(x ** 2)) / env, 10 ** (-9 / 20), 10 ** (9 / 20))
    return x * gain


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
        seeds = STEADY_SEEDS if kind == "steady" else (args.seed,)
        negative = NEGATIVE + STEADY_NEGATIVE if kind == "steady" else NEGATIVE
        for seed in seeds:
            rp = raw_path(clip_id, prompt, seconds, seed)
            if not os.path.exists(rp) and (only is None or clip_id in only):
                if not token:
                    sys.exit("REPLICATE_API_TOKEN is not set")
                print(f"generating {clip_id} seed {seed} ({seconds}s)...", flush=True)
                data, url = replicate(prompt, seconds, seed, token, negative)
                decode_any(data, rp)
        takes = [(s, raw_path(clip_id, prompt, seconds, s)) for s in seeds]
        takes = [(s, t) for s, t in takes if os.path.exists(t)]
        if not takes:
            print(f"skip {clip_id}: not generated yet")
            continue

        used_seed = takes[0][0]
        if kind == "steady":
            best_score = 1e9
            for s, t in takes:
                r, sr = sf.read(t, dtype="float64")
                part, score = steadiest(highpass(trim_silence(to_mono(r))))
                print(f"  {clip_id} seed {s}: steadiness score {score:.1f}")
                if score < best_score:
                    x, best_score, used_seed = part, score, s
            x = flatten_loudness(x)
        else:
            x, sr = sf.read(takes[0][1], dtype="float64")
            assert sr == SAMPLE_RATE, sr
            x = highpass(trim_silence(to_mono(x)))
        if kind != "oneshot":
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
                             "prompt": prompt, "seed": used_seed}
        print(f"{clip_id}: {manifest[clip_id]['seconds']}s -> {os.path.getsize(out) // 1024} KB")

    with open(os.path.join(OUT_DIR, "bank.json"), "w", encoding="utf-8") as f:
        json.dump({"model": "stability-ai/stable-audio-open-1.0", "replicate_version": MODEL_VERSION,
                   "sample_rate": SAMPLE_RATE, "clips": manifest}, f, indent=1)


if __name__ == "__main__":
    main()
