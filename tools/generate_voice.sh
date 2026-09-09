#!/bin/bash
# Renders every voice line with macOS's Zarvox voice (a synthesiser, not a human — the game's system
# voice is a machine reading the program's status) and hardens it into a metallic, ring-modulated,
# lightly bit-crushed AAC (m4a) for assets/voice/. Writes voice/manifest.json with each clip's duration in
# ms so the app can queue lines back to back and time the intro crawl to the narration.
#   tools/generate_voice.sh            (needs: say, ffmpeg, ffprobe, python3)
set -e
cd "$(dirname "$0")/.."
OUT=app/src/main/assets/voice
TMP=$(mktemp -d)
mkdir -p "$OUT"
render() {  # id | text | rate
  local id="$1" text="$2" rate="${3:-150}"
  say -v Zarvox -r "$rate" -o "$TMP/$id.aiff" "$text"
  ffmpeg -y -v error -i "$TMP/$id.aiff" \
    -af "highpass=f=160,lowpass=f=3900,tremolo=f=34:d=0.45,aecho=0.85:0.55:11:0.28,acrusher=bits=10:mode=log:aa=1,volume=2.2,alimiter=limit=0.95" \
    -ac 1 -ar 22050 -c:a aac -b:a 56k "$OUT/$id.m4a"
}
render intro_1 "Greetings, program."
render intro_2 "A programmer wrote a game in a basement."
render intro_3 "The game was stolen."
render intro_4 "The thief built the Monopoly Control Protocol on its bones."
render intro_5 "Now the Recognizers hunt whoever remembers the original code."
render intro_6 "You remember."
render intro_7 "Find the Bit. Survive the waves."
render intro_8 "End of line."
for n in 1 2 3 4 5 6 7 8 9 10 11 12; do render "wave_$n" "Wave $n." 160; done
render wave_more "New wave." 160
render incoming "Recognizers incoming." 160
render destroyed "Recognizer destroyed." 170
render find_bit "Find the Bit." 160
render bit "Bit acquired. Extra life." 160
# The energy pool. The machine reports the theft of its own power flatly, as it reports everything,
# and stays SHORT — the pilot answers both of these (assets/voice_hero: hero_shield_up says "Energy.
# Shields holding.", hero_shield_down says "Shield's gone."), and a two-clause system line pushed the
# answer nearly three seconds past a moment that wants to land while you are still standing in the
# pool. One clause each; the echo between the two voices is the point.
render energy "Energy drawn." 160
render shield_down "Shield collapsed." 165
render hit "Tank hit." 175
render lockon "Warning. Recognizer locked on." 170
render wave_clear "Wave cleared." 160
render last_life "Last life." 165
render derezzed "Derezzed." 150
render game_over "Game over." 145
render end_of_line "End of line." 145
render high_score "New high score." 160
render paused "Program paused." 165
render resumed "Program resumed." 165
render recentred "Heading reset." 170
# durations → manifest
python3 - "$OUT" <<'PY'
import json, os, subprocess, sys
out = sys.argv[1]; m = {}
for f in sorted(os.listdir(out)):
    if not f.endswith('.m4a'): continue
    d = subprocess.run(['ffprobe','-v','error','-show_entries','format=duration','-of','csv=p=0', os.path.join(out,f)], capture_output=True, text=True).stdout.strip()
    m[f[:-4]] = int(float(d) * 1000)
json.dump(m, open(os.path.join(out,'manifest.json'),'w'), indent=1, sort_keys=True)
print(f"{len(m)} clips, total {sum(m.values())/1000:.1f} s")
PY
rm -rf "$TMP"
