#!/usr/bin/env python3
"""Write deterministic camera-sized JPEGs for the preview benchmark (needs Pillow).

  python3 tools/make_bench_fixtures.py build/bench-fixtures [count]

Real RAW files cannot be generated, so these exercise the JPEG sidecar path and the prefetch
scheduling. Add real RAW samples to the same directory to cover the embedded-JPEG path.
"""
import random
import sys
from pathlib import Path

from PIL import Image, ImageChops, ImageDraw

WIDTH, HEIGHT = 6000, 4000  # a 24 MP body


def make(index: int) -> Image.Image:
    rng = random.Random(index)
    # Gradients and shapes give structure; grain (below) brings the file to a realistic size.
    image = Image.linear_gradient("L").resize((WIDTH, HEIGHT)).convert("RGB")
    draw = ImageDraw.Draw(image)
    for _ in range(160):
        x, y = rng.randrange(WIDTH), rng.randrange(HEIGHT)
        r = rng.randrange(40, 600)
        colour = tuple(rng.randrange(256) for _ in range(3))
        draw.ellipse((x - r, y - r, x + r, y + r), fill=colour)
    # Sensor-like grain: without it the JPEG is ~1 MB and far cheaper to decode than a real 24 MP shot.
    grain = Image.effect_noise((WIDTH, HEIGHT), 28).convert("RGB")
    return Image.blend(image, ImageChops.add(image, grain, scale=1.0, offset=-128), 0.5)


def main() -> int:
    out = Path(sys.argv[1] if len(sys.argv) > 1 else "build/bench-fixtures")
    count = int(sys.argv[2]) if len(sys.argv) > 2 else 8
    out.mkdir(parents=True, exist_ok=True)
    for index in range(count):
        path = out / f"BENCH_{index:04d}.JPG"
        make(index).save(path, "JPEG", quality=92)
        print(f"{path} {path.stat().st_size / 1e6:.1f} MB")
    return 0


if __name__ == "__main__":
    sys.exit(main())
