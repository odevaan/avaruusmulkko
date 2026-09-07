"""
Generates complete professional Android icon sets (adaptive and legacy)
from art/avaruusmulkko.jpg.
"""

from pathlib import Path
from PIL import Image, ImageDraw, ImageOps

ART_PATH = Path("art/mulkko.jpg")
RES_DIR = Path("mobile/app/src/main/res")

DENSITIES = {
    "mipmap-mdpi": (48, 108),
    "mipmap-hdpi": (72, 162),
    "mipmap-xhdpi": (96, 216),
    "mipmap-xxhdpi": (144, 324),
    "mipmap-xxxhdpi": (192, 432),
}


def make_circular(img: Image.Image) -> Image.Image:
    """Mask image with a smooth circular mask."""
    size = img.size
    mask = Image.new("L", size, 0)
    draw = ImageDraw.Draw(mask)
    draw.ellipse((0, 0, size[0], size[1]), fill=255)
    output = ImageOps.fit(img, size, centering=(0.5, 0.5))
    output.putalpha(mask)
    return output


def make_rounded(img: Image.Image, radius_ratio: float = 0.2) -> Image.Image:
    """Mask image with rounded corners."""
    size = img.size
    radius = int(size[0] * radius_ratio)
    mask = Image.new("L", size, 0)
    draw = ImageDraw.Draw(mask)
    draw.rounded_rectangle((0, 0, size[0], size[1]), radius=radius, fill=255)
    output = ImageOps.fit(img, size, centering=(0.5, 0.5))
    output.putalpha(mask)
    return output


def generate_icons():
    assert ART_PATH.exists(), f"Art image missing at {ART_PATH}"
    img = Image.open(ART_PATH).convert("RGBA")
    w, h = img.size

    # Crop to central square
    min_dim = min(w, h)
    left = (w - min_dim) // 2
    top = (h - min_dim) // 2
    square_img = img.crop((left, top, left + min_dim, top + min_dim))

    # Save high-res 512x512 play store icon
    play_icon = square_img.resize((512, 512), Image.Resampling.LANCZOS)
    play_icon.save(RES_DIR / "ic_launcher-playstore.png", "PNG")

    for folder_name, (legacy_size, adaptive_size) in DENSITIES.items():
        out_folder = RES_DIR / folder_name
        out_folder.mkdir(parents=True, exist_ok=True)

        # 1. Standard square legacy icon (with subtle rounded corners)
        legacy_icon = square_img.resize((legacy_size, legacy_size), Image.Resampling.LANCZOS)
        rounded_legacy = make_rounded(legacy_icon, radius_ratio=0.18)
        rounded_legacy.save(out_folder / "ic_launcher.png", "PNG")

        # 2. Round legacy icon
        round_icon = make_circular(legacy_icon)
        round_icon.save(out_folder / "ic_launcher_round.png", "PNG")

        # 3. Adaptive Foreground:
        # In Android adaptive icons, the full canvas is 108x108 dp, but the safe zone is the inner 72x72 dp (66% scale).
        # We place the square artwork centered at 72% scale with a transparent background.
        fg_canvas = Image.new("RGBA", (adaptive_size, adaptive_size), (0, 0, 0, 0))
        fg_art_size = int(adaptive_size * 0.72)
        resized_art = square_img.resize((fg_art_size, fg_art_size), Image.Resampling.LANCZOS)
        # Apply circular mask to the artwork on the foreground so it looks super clean
        circular_art = make_circular(resized_art)

        paste_pos = ((adaptive_size - fg_art_size) // 2, (adaptive_size - fg_art_size) // 2)
        fg_canvas.paste(circular_art, paste_pos, circular_art)
        fg_canvas.save(out_folder / "ic_launcher_foreground.png", "PNG")

    # 4. Adaptive XML definitions
    anydpi_dir = RES_DIR / "mipmap-anydpi-v26"
    anydpi_dir.mkdir(parents=True, exist_ok=True)

    ic_launcher_xml = """<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@color/ic_launcher_background"/>
    <foreground android:drawable="@mipmap/ic_launcher_foreground"/>
</adaptive-icon>
"""
    (anydpi_dir / "ic_launcher.xml").write_text(ic_launcher_xml, encoding="utf-8")
    (anydpi_dir / "ic_launcher_round.xml").write_text(ic_launcher_xml, encoding="utf-8")

    # 5. Background color definition
    values_dir = RES_DIR / "values"
    colors_xml_path = values_dir / "colors.xml"
    colors_content = """<?xml version="1.0" encoding="utf-8"?>
<resources>
    <color name="ic_launcher_background">#000000</color>
</resources>
"""
    colors_xml_path.write_text(colors_content, encoding="utf-8")

    print("[+] Successfully generated all Android icons in mipmap folders!")


if __name__ == "__main__":
    generate_icons()
