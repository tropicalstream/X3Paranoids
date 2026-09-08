#!/usr/bin/env python3
"""The protagonist's voice — the pilot in the tank — rendered with fish.audio.

The system voice (assets/voice/, macOS Zarvox through a ring-modulator chain) is the GAME talking:
flat, machine, indifferent. This is the other half of the conversation — the program in the tank who
remembers the original code, and who is the only thing in here that sounds alive. Keeping the two in
separate folders keeps that distinction honest and lets each be regenerated without touching the other.

    tools/generate_hero_voice.py [--force] [--dry-run] [--local]

Two engines. FISH (default) uses the owner's chosen voice model on fish.audio and is the intended
one. LOCAL (--local, and the automatic fallback when fish answers 402) uses the most natural macOS
voice through a helmet-mic chain: band-limited to 320-3300 Hz, compressed, with a tight room and a
touch of drive, so the pilot reads as a HUMAN ON A COMMS CHANNEL and never blurs into the system
voice, which is a ring-modulated machine. Swap back with a single re-run once fish has API credit.

Reads FISH_API_KEY and HERO_VOICE_MODEL_ID from tools/fish.config (gitignored) or the environment.
Writes app/src/main/assets/voice_hero/<id>.mp3 plus manifest.json (id -> duration in ms), loudness-
normalised with ffmpeg so the pilot never jumps out over the system voice.
"""
import json, os, subprocess, sys, urllib.error, urllib.request

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(ROOT, "app/src/main/assets/voice_hero")
CONFIG = os.path.join(ROOT, "tools", "fish.config")
API = "https://api.fish.audio/v1/tts"
TARGET_LUFS = "-17"          # a shade under the system voice so the machine still dominates
# Of the voices macOS actually ships, the old formant synths (Ralph, Fred, Albert) sound robotic —
# which is precisely the register the SYSTEM voice already owns. The pilot has to be the human in the
# room, so the natural voice wins even though it is less characterful on its own; the comms chain
# supplies the character.
LOCAL_VOICE, LOCAL_RATE = "Samantha", 168
COMMS = ("highpass=f=320,lowpass=f=3300,"
         "acompressor=threshold=-18dB:ratio=4:attack=5:release=90,"
         "aecho=0.9:0.35:22:0.18,volume=2.0,alimiter=limit=0.95")

# The pilot wrote the game that was stolen; every line should sound like someone who has been here
# before. Sparse and dry — a tank pilot, not a narrator.
LINES = [
    ("hero_start",       "I'm in. Let's see what they built on my code."),
    ("hero_wave",        "Another wave."),
    ("hero_wave_late",   "They just keep coming."),
    ("hero_kill_1",      "Derezzed."),
    ("hero_kill_2",      "That's one."),
    ("hero_kill_3",      "Down you go."),
    ("hero_kill_streak", "I remember every corner of this maze."),
    ("hero_hit",         "I'm hit."),
    ("hero_hit_bad",     "Hull's failing."),
    ("hero_last_life",   "One life left. Make it count."),
    ("hero_shield_up",   "Energy. Shields holding."),
    ("hero_shield_hit",  "Shield's taking it."),
    ("hero_shield_down", "Shield's gone."),
    ("hero_bit_near",    "The Bit's close."),
    ("hero_bit_get",     "There you are."),
    ("hero_wave_clear",  "Sector clear."),
    ("hero_quiet",       "The grid's quiet. For now."),
    ("hero_mcp",         "The Protocol doesn't own this maze."),
    ("hero_derez",       "No. Not like this."),
    ("hero_game_over",   "They can steal the game. They can't steal the code."),
    ("hero_high_score",  "A new record."),
]


def cfg():
    out = {}
    if os.path.exists(CONFIG):
        for line in open(CONFIG, encoding="utf-8"):
            if "=" in line and not line.strip().startswith("#"):
                k, v = line.split("=", 1)
                out[k.strip()] = v.strip()
    return out


def normalize(audio):
    """Loudness-normalise so the pilot sits consistently under the system voice. No-op without ffmpeg."""
    try:
        p = subprocess.run(
            ["ffmpeg", "-hide_banner", "-loglevel", "error", "-i", "pipe:0",
             "-af", f"loudnorm=I={TARGET_LUFS}:TP=-1.5:LRA=11", "-f", "mp3", "pipe:1"],
            input=audio, capture_output=True, check=True)
        return (p.stdout, True) if len(p.stdout) > 512 else (audio, False)
    except Exception:
        return audio, False


def duration_ms(path):
    try:
        d = subprocess.run(["ffprobe", "-v", "error", "-show_entries", "format=duration",
                            "-of", "csv=p=0", path], capture_output=True, text=True, check=True)
        return int(float(d.stdout.strip()) * 1000)
    except Exception:
        return 0


def render_local(text, path):
    """macOS `say` + the comms chain. Returns True on success."""
    aiff = path + ".aiff"
    try:
        subprocess.run(["say", "-v", LOCAL_VOICE, "-r", str(LOCAL_RATE), "-o", aiff, text], check=True)
        subprocess.run(["ffmpeg", "-y", "-hide_banner", "-loglevel", "error", "-i", aiff,
                        "-af", COMMS + f",loudnorm=I={TARGET_LUFS}:TP=-1.5:LRA=11",
                        "-ar", "22050", "-ac", "1", path], check=True)
        return True
    except Exception as e:
        print(f"FAIL  local {os.path.basename(path)}: {e}", file=sys.stderr)
        return False
    finally:
        if os.path.exists(aiff): os.remove(aiff)


def main():
    force, dry = "--force" in sys.argv, "--dry-run" in sys.argv
    local = "--local" in sys.argv
    c = cfg()
    key = os.environ.get("FISH_API_KEY") or c.get("FISH_API_KEY")
    model = os.environ.get("HERO_VOICE_MODEL_ID") or c.get("HERO_VOICE_MODEL_ID")
    if not dry and not local and not key:
        sys.exit("Set FISH_API_KEY in tools/fish.config or the environment, or pass --local.")

    os.makedirs(OUT, exist_ok=True)
    done = skipped = failed = 0
    for cid, text in LINES:
        path = os.path.join(OUT, f"{cid}.mp3")
        if dry:
            print(f"DRY {cid}: {text}"); done += 1; continue
        if os.path.exists(path) and not force:
            skipped += 1; continue
        if local:
            if render_local(text, path):
                print(f"  OK  {cid} [local]: \"{text}\""); done += 1
            else: failed += 1
            continue
        body = json.dumps({"text": text, "reference_id": model, "format": "mp3"}).encode()
        req = urllib.request.Request(API, data=body, method="POST", headers={
            "Authorization": f"Bearer {key}", "Content-Type": "application/json"})
        try:
            with urllib.request.urlopen(req, timeout=90) as r:
                audio = r.read()
            if len(audio) < 512:
                raise RuntimeError(f"suspiciously small response ({len(audio)} bytes)")
            audio, normed = normalize(audio)
            open(path, "wb").write(audio)
            print(f"  OK  {cid}: \"{text}\"{'' if normed else '  [unnormalised — no ffmpeg]'}")
            done += 1
        except urllib.error.HTTPError as e:
            detail = e.read().decode()[:120]
            print(f"FAIL  {cid}: HTTP {e.code} {detail}", file=sys.stderr)
            if e.code == 402:
                # The account's API credit is separate from platform credit and is exhausted. There is
                # nothing to retry here, so fall back rather than hammering a paid endpoint 21 times.
                print("\nfish.audio reports insufficient API CREDIT (separate from platform credit).",
                      "\nFalling back to the local pilot voice; re-run without --local once credit is topped up.",
                      file=sys.stderr)
                local = True
                if render_local(text, path):
                    print(f"  OK  {cid} [local fallback]: \"{text}\""); done += 1
                else: failed += 1
                continue
            failed += 1
        except Exception as e:
            print(f"FAIL  {cid}: {e}", file=sys.stderr); failed += 1

    if not dry:
        manifest = {cid: duration_ms(os.path.join(OUT, f"{cid}.mp3"))
                    for cid, _ in LINES if os.path.exists(os.path.join(OUT, f"{cid}.mp3"))}
        json.dump(manifest, open(os.path.join(OUT, "manifest.json"), "w"), indent=1, sort_keys=True)
        total = sum(manifest.values()) / 1000
        print(f"\n{done} generated, {skipped} present, {failed} failed — {len(manifest)} clips, {total:.1f} s total")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
