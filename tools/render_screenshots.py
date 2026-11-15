"""
Renders every TaskXX/output/*.txt console capture (made by run_all.sh) into a
terminal-style PNG at TaskXX/screenshots/<name>.png.

Usage: python3 tools/render_screenshots.py      (from the repository root)
Needs: Pillow  (pip install pillow)
"""
from pathlib import Path
from PIL import Image, ImageDraw, ImageFont

ROOT = Path(__file__).resolve().parent.parent
FONT_PATHS = ["/usr/share/fonts/truetype/dejavu/DejaVuSansMono.ttf",
              "C:/Windows/Fonts/consola.ttf", "/System/Library/Fonts/Menlo.ttc"]
SIZE = 15
BG, FG = (30, 30, 30), (220, 220, 220)
PROMPT, OK, BAD, HEAD = (110, 200, 110), (110, 200, 110), (240, 120, 90), (120, 170, 240)
BAR = (55, 55, 55)


def font():
    for p in FONT_PATHS:
        if Path(p).exists():
            return ImageFont.truetype(p, SIZE)
    return ImageFont.load_default()


def colour(line):
    if line.startswith("$ "):
        return PROMPT
    if "LOST" in line or "LOGGED IN as 'Transport" in line or "ACCEPTED although" in line or "FAILED" in line or "FAIL" == line.rstrip()[-4:] or "HACKED" in line or "is lost" in line:
        return BAD
    if line.rstrip().endswith("PASS") or "tests passed" in line or "(conserved)" in line or "REJECTED" in line:
        return OK
    if line.startswith("===") or line.startswith("ID "):
        return HEAD
    return FG


def render(txt: Path, out: Path, title: str):
    f = font()
    lines = txt.read_text(encoding="utf-8").expandtabs(4).splitlines() or [""]
    cw = f.getbbox("M")[2]
    lh = SIZE + 5
    width = max(700, 40 + cw * max(len(l) for l in lines))
    height = 46 + lh * len(lines) + 20
    img = Image.new("RGB", (width, height), BG)
    d = ImageDraw.Draw(img)
    d.rectangle([0, 0, width, 30], fill=BAR)
    for i, c in enumerate([(237, 106, 94), (245, 191, 79), (98, 197, 84)]):
        d.ellipse([12 + i * 22, 9, 26 + i * 22, 23], fill=c)
    d.text((90, 7), title, fill=(200, 200, 200), font=f)
    y = 42
    for line in lines:
        d.text((20, y), line, fill=colour(line), font=f)
        y += lh
    out.parent.mkdir(parents=True, exist_ok=True)
    img.save(out)


def main():
    for txt in sorted(ROOT.glob("Task*/output/*.txt")):
        task = txt.parent.parent.name
        png = txt.parent.parent / "screenshots" / (txt.stem + ".png")
        render(txt, png, f"Terminal - {task} - {txt.stem}")
        print("  ", png.relative_to(ROOT))


if __name__ == "__main__":
    main()
