#!/usr/bin/env python3
"""Generates the mod's GUI sprites.

    python tools/make_sprites.py [path-to-the-Better-Pets-GUI-artwork]

Everything the screens are drawn with lives in
src/main/resources/assets/betterpets-quickslots/textures/gui/sprites/ and is produced here, so the look
can be changed in one place. Two kinds of sprites come out:

  * drawn here, pixel by pixel, in the wood / copper / teal palette of the Better Pets menu artwork
    (panels, slots, buttons, switches, the wheel, ...). Stretchable ones get a nine-slice .mcmeta;
  * taken from that artwork as they are (icons, the characters that lean on the panels).

The generated files are committed, so building the mod does not need the artwork. Re-run this only to
change the look. Needs Pillow.
"""
import json
import math
import os
import shutil
import sys

from PIL import Image

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(HERE, "..", "src", "main", "resources", "assets", "betterpets-quickslots", "textures", "gui", "sprites")
ART = sys.argv[1] if len(sys.argv) > 1 else r"C:/Users/Kamil Bura/Projekten/Better Pets26.1.2/paper-plugin/GUI"


def rgb(hex_value, alpha=255):
    hex_value = hex_value.lstrip("#")
    return (int(hex_value[0:2], 16), int(hex_value[2:4], 16), int(hex_value[4:6], 16), alpha)


# --- palette, sampled from BetterPets_Exact_GUI/main.png ---------------------------------------------
CLEAR = (0, 0, 0, 0)
OUTLINE = rgb("342017")      # outermost line of every panel
BEVEL_DARK = rgb("513627")
BEVEL_LIGHT = rgb("906241")
BODY = rgb("392920")         # panel fill
BODY_LIGHT = rgb("46332a")
BODY_SHADE = rgb("2e211a")
PLANK_SEAM = rgb("33241c")     # the seams between the planks of a panel: barely darker and lighter
PLANK_LIGHT = rgb("3c2c22")    # than the wood itself
SLOT = rgb("302320")
SLOT_SHADOW = rgb("161113")
SLOT_EDGE = rgb("211615")
GRID = rgb("71533f")
COPPER_DEEP = rgb("290f08")
COPPER_DARK = rgb("7d381d")
COPPER = rgb("b65c36")
COPPER_LIGHT = rgb("d47242")
COPPER_SHINE = rgb("f8a166")
TEAL_DEEP = rgb("072822")
TEAL_DARK = rgb("0e3830")
TEAL = rgb("1f6b5c")
TEAL_LIGHT = rgb("3aa58c")
TEAL_SHINE = rgb("9fe8d4")
GOLD_DEEP = rgb("6b3d0c")
GOLD_DARK = rgb("b9771a")
GOLD = rgb("ffc83c")
GOLD_SHINE = rgb("fff0a8")
GREEN_DARK = rgb("1c6b2a")
GREEN = rgb("5be36a")
GREEN_SHINE = rgb("c8ffcf")
STEEL_DARK = rgb("3a3640")
STEEL = rgb("8a8694")
STEEL_LIGHT = rgb("c9c5d2")


def new(width, height, fill=CLEAR):
    return Image.new("RGBA", (width, height), fill)


def rect(image, x0, y0, x1, y1, colour):
    """Fills the half-open box [x0, x1) x [y0, y1)."""
    for y in range(max(0, y0), min(image.height, y1)):
        for x in range(max(0, x0), min(image.width, x1)):
            image.putpixel((x, y), colour)


def frame(image, inset, colour):
    """A one-pixel rectangle outline, `inset` pixels in from the edge."""
    w, h = image.size
    rect(image, inset, inset, w - inset, inset + 1, colour)
    rect(image, inset, h - inset - 1, w - inset, h - inset, colour)
    rect(image, inset, inset, inset + 1, h - inset, colour)
    rect(image, w - inset - 1, inset, w - inset, h - inset, colour)


def knock_corners(image, size=1):
    """Clears the corner pixels so a box reads as slightly rounded."""
    w, h = image.size
    for dx in range(size):
        for dy in range(size - dx):
            for x, y in ((dx, dy), (w - 1 - dx, dy), (dx, h - 1 - dy), (w - 1 - dx, h - 1 - dy)):
                image.putpixel((x, y), CLEAR)


def save(image, name, nine_slice=None, stretch_inner=False):
    path = os.path.join(OUT, name + ".png")
    os.makedirs(os.path.dirname(path), exist_ok=True)
    image.save(path)
    meta = path + ".mcmeta"
    if nine_slice is not None:
        scaling = {"type": "nine_slice", "width": image.width, "height": image.height, "border": nine_slice}
        if stretch_inner:
            scaling["stretch_inner"] = True          # stretch the middle instead of tiling it
        with open(meta, "w", encoding="utf-8", newline="\n") as handle:
            json.dump({"gui": {"scaling": scaling}}, handle, indent=2)
            handle.write("\n")
    elif os.path.exists(meta):
        os.remove(meta)


# --- panels -------------------------------------------------------------------------------------------

def gem(image, x, y, dark, mid, light, shine):
    """A 3x3 cut gem with its highlight top-left."""
    for dy in range(3):
        for dx in range(3):
            image.putpixel((x + dx, y + dy), mid)
    image.putpixel((x, y), shine)
    image.putpixel((x + 1, y), light)
    image.putpixel((x, y + 1), light)
    image.putpixel((x + 2, y + 2), dark)
    image.putpixel((x + 1, y + 2), dark)
    image.putpixel((x + 2, y + 1), dark)


def corner_bracket(image, flip_x, flip_y):
    """A copper bracket holding a teal gem, in one corner of a panel."""
    w, h = image.size

    def put(x, y, colour):
        image.putpixel((w - 1 - x if flip_x else x, h - 1 - y if flip_y else y), colour)

    for i in range(3, 10):
        put(i, 3, COPPER_LIGHT if i < 8 else COPPER)
        put(3, i, COPPER_LIGHT if i < 8 else COPPER)
        put(i, 4, COPPER_DARK)
        put(4, i, COPPER_DARK)
    put(3, 3, COPPER_SHINE)
    put(9, 4, COPPER_DEEP)
    put(4, 9, COPPER_DEEP)
    # the gem sits inside the bracket's elbow
    gx = w - 1 - 7 if flip_x else 5
    gy = h - 1 - 7 if flip_y else 5
    gem(image, gx, gy, TEAL_DEEP, TEAL, TEAL_LIGHT, TEAL_SHINE)


def make_panel():
    image = new(40, 40, BODY)
    frame(image, 0, OUTLINE)
    frame(image, 1, BEVEL_DARK)
    frame(image, 2, BEVEL_LIGHT)
    w, h = image.size
    # light falls from the top left: the lower and right bevel are in shade
    rect(image, 2, h - 3, w - 2, h - 2, BEVEL_DARK)
    rect(image, w - 3, 2, w - 2, h - 2, BEVEL_DARK)
    rect(image, 3, 3, w - 3, 4, BODY_LIGHT)
    rect(image, 3, h - 4, w - 3, h - 3, BODY_SHADE)
    # Planks: a seam every eight pixels. The middle of the panel is tiled (it is a 16 pixel square
    # between the 12 pixel borders), and so are its left and right edges, so the seams run right across.
    for seam in (19, 27):
        rect(image, 3, seam, w - 3, seam + 1, PLANK_SEAM)
        rect(image, 3, seam + 1, w - 3, seam + 2, PLANK_LIGHT)
    for flip_x in (False, True):
        for flip_y in (False, True):
            corner_bracket(image, flip_x, flip_y)
    knock_corners(image, 1)
    save(image, "panel", nine_slice=12)


def make_tooltip():
    """
    The mod's own tooltip look: dark wood with a copper line, in place of the game's purple.

    The game draws a tooltip's two sprites 12 pixels larger on every side than the text: 3 of padding and
    a margin of 9 that these leave empty, like the originals do.
    """
    margin = 9
    size = 40
    background = new(size, size)
    rect(background, margin - 1, margin - 1, size - margin + 1, size - margin + 1, rgb("241812", 246))
    for x, y in ((margin - 1, margin - 1), (size - margin, margin - 1), (margin - 1, size - margin), (size - margin, size - margin)):
        background.putpixel((x, y), CLEAR)
    save(background, "tooltip/wood_background", nine_slice=margin)

    border = new(size, size)
    low, high = margin, size - margin - 1
    for i in range(low, high + 1):
        shade = COPPER_LIGHT if i < size // 2 else COPPER_DARK      # lit from above, like the panels
        border.putpixel((low, i), shade)
        border.putpixel((high, i), shade)
    rect(border, low, low, high + 1, low + 1, COPPER_SHINE)
    rect(border, low, high, high + 1, high + 1, COPPER_DEEP)
    save(border, "tooltip/wood_frame", nine_slice=margin + 1, stretch_inner=True)


def make_inset():
    image = new(16, 16, SLOT)
    frame(image, 0, GRID)
    frame(image, 1, SLOT_EDGE)
    rect(image, 2, 2, 14, 3, SLOT_SHADOW)
    rect(image, 2, 2, 3, 14, SLOT_SHADOW)
    save(image, "inset", nine_slice=4)


def make_slot():
    # the cell of the menu artwork, as a free-standing tile
    image = new(18, 18, SLOT)
    frame(image, 0, GRID)
    frame(image, 1, SLOT_EDGE)
    rect(image, 2, 2, 16, 3, SLOT_SHADOW)
    rect(image, 2, 2, 3, 16, SLOT_SHADOW)
    save(image, "slot", nine_slice=3)


def make_gold_frame():
    image = new(24, 24)
    frame(image, 0, GOLD_DEEP)
    frame(image, 1, GOLD)
    frame(image, 2, GOLD_DARK)
    frame(image, 3, GOLD_DEEP)
    w, h = image.size
    rect(image, 1, 1, w - 1, 2, GOLD_SHINE)
    rect(image, 1, 1, 2, h - 1, GOLD_SHINE)
    knock_corners(image, 2)
    image.putpixel((1, 1), GOLD)
    image.putpixel((w - 2, 1), GOLD)
    image.putpixel((1, h - 2), GOLD)
    image.putpixel((w - 2, h - 2), GOLD_DARK)
    save(image, "frame_gold", nine_slice=5)


def make_card(name, fill, top, rim):
    image = new(24, 24, fill)
    frame(image, 0, OUTLINE)
    frame(image, 1, rim)
    w, h = image.size
    rect(image, 2, 2, w - 2, 3, top)
    rect(image, 2, h - 3, w - 2, h - 2, BODY_SHADE)
    knock_corners(image, 1)
    save(image, name, nine_slice=5)


def make_button(name, fill, top, rim, rim_light):
    image = new(24, 20, fill)
    frame(image, 0, OUTLINE)
    frame(image, 1, rim)
    w, h = image.size
    rect(image, 1, 1, w - 1, 2, rim_light)
    rect(image, 1, 1, 2, h - 1, rim_light)
    rect(image, 2, 2, w - 2, 3, top)
    rect(image, 2, h - 3, w - 2, h - 2, BODY_SHADE)
    knock_corners(image, 1)
    save(image, name, nine_slice=5)


def make_tab(name, fill, rim, accent):
    image = new(24, 18, fill)
    frame(image, 0, OUTLINE)
    frame(image, 1, rim)
    w, h = image.size
    if accent is not None:
        rect(image, 3, h - 3, w - 3, h - 1, accent)
    knock_corners(image, 2)
    save(image, name, nine_slice=5)


# --- controls -----------------------------------------------------------------------------------------

def pill(width, height, outline, fill, top):
    """A box whose ends are rounded as far as its height allows."""
    image = new(width, height)
    radius = height / 2.0
    for y in range(height):
        for x in range(width):
            cx = min(max(x + 0.5, radius), width - radius)
            distance = math.hypot(x + 0.5 - cx, y + 0.5 - radius)
            if distance <= radius - 1.0:
                image.putpixel((x, y), top if y < 3 and distance <= radius - 2.0 else fill)
            elif distance <= radius:
                image.putpixel((x, y), outline)
    return image


def make_toggle():
    save(pill(30, 14, OUTLINE, SLOT, SLOT_SHADOW), "toggle_off")
    save(pill(30, 14, TEAL_DEEP, TEAL, TEAL_LIGHT), "toggle_on")
    knob = new(12, 12)
    for y in range(12):
        for x in range(12):
            distance = math.hypot(x - 5.5, y - 5.5)
            if distance <= 4.6:
                knob.putpixel((x, y), COPPER_LIGHT if (x + y) < 10 else COPPER)
            elif distance <= 5.7:
                knob.putpixel((x, y), COPPER_DEEP)
    knob.putpixel((4, 3), COPPER_SHINE)
    knob.putpixel((3, 4), COPPER_SHINE)
    knob.putpixel((4, 4), COPPER_SHINE)
    save(knob, "toggle_knob")


def make_slider():
    track = new(12, 6, SLOT)
    frame(track, 0, GRID)
    rect(track, 1, 1, 11, 2, SLOT_SHADOW)
    save(track, "slider_track", nine_slice=2)
    fill = new(12, 6, TEAL)
    frame(fill, 0, TEAL_DEEP)
    rect(fill, 1, 1, 11, 2, TEAL_LIGHT)
    save(fill, "slider_fill", nine_slice=2)
    knob = new(8, 14, COPPER)
    frame(knob, 0, COPPER_DEEP)
    rect(knob, 1, 1, 7, 2, COPPER_SHINE)
    rect(knob, 1, 1, 2, 13, COPPER_LIGHT)
    rect(knob, 3, 5, 5, 9, COPPER_DARK)
    knock_corners(knob, 1)
    save(knob, "slider_knob")


def make_key_cap():
    image = new(16, 14, STEEL_DARK)
    frame(image, 0, rgb("1c1a20"))
    w, h = image.size
    rect(image, 1, 1, w - 1, 2, STEEL)
    rect(image, 1, 1, 2, h - 3, STEEL)
    rect(image, 1, h - 3, w - 1, h - 1, rgb("24222a"))
    knock_corners(image, 1)
    save(image, "key_cap", nine_slice=4)


# --- the wheel ----------------------------------------------------------------------------------------

def make_ring(size, outer, inner):
    image = new(size, size)
    centre = size / 2.0
    for y in range(size):
        for x in range(size):
            distance = math.hypot(x + 0.5 - centre, y + 0.5 - centre)
            if distance > outer or distance < inner:
                continue
            angle = math.atan2(y + 0.5 - centre, x + 0.5 - centre)
            lit = math.cos(angle + math.pi * 0.75)          # +1 at the top left, -1 at the bottom right
            if distance > outer - 1.2 or distance < inner + 1.2:
                colour = OUTLINE
            elif distance > outer - 2.4:
                colour = BEVEL_LIGHT if lit > 0 else BEVEL_DARK
            elif distance < inner + 2.4:
                colour = BEVEL_DARK if lit > 0 else BEVEL_LIGHT
            elif abs(distance - (outer + inner) / 2.0) < 0.9:
                colour = BODY_SHADE                              # a groove down the middle of the band
            else:
                colour = BODY_LIGHT if lit > 0.6 else BODY
            image.putpixel((x, y), colour)
    return image


def make_wheel():
    save(make_ring(200, 98, 80), "wheel_ring")
    save(make_ring(88, 43, 33), "wheel_ring_small")

    hub = new(72, 72)
    for y in range(72):
        for x in range(72):
            distance = math.hypot(x - 35.5, y - 35.5)
            lit = math.cos(math.atan2(y - 35.5, x - 35.5) + math.pi * 0.75)
            if distance <= 27:
                hub.putpixel((x, y), SLOT if distance > 2 else SLOT_EDGE)
            elif distance <= 28.4:
                hub.putpixel((x, y), SLOT_SHADOW if lit > 0 else GRID)
            elif distance <= 32:
                hub.putpixel((x, y), COPPER_LIGHT if lit > 0.5 else COPPER if lit > -0.4 else COPPER_DARK)
            elif distance <= 33.4:
                hub.putpixel((x, y), COPPER_DEEP)
    save(hub, "wheel_hub")

    node = new(44, 44)
    for y in range(44):
        for x in range(44):
            distance = math.hypot(x - 21.5, y - 21.5)
            lit = math.cos(math.atan2(y - 21.5, x - 21.5) + math.pi * 0.75)
            if distance <= 17:
                node.putpixel((x, y), SLOT)
            elif distance <= 18.2:
                node.putpixel((x, y), SLOT_SHADOW if lit > 0 else SLOT_EDGE)
            elif distance <= 20:
                node.putpixel((x, y), BEVEL_LIGHT if lit > 0.3 else GRID if lit > -0.5 else BEVEL_DARK)
            elif distance <= 21.2:
                node.putpixel((x, y), OUTLINE)
    save(node, "wheel_node")

    gold = new(48, 48)
    for y in range(48):
        for x in range(48):
            distance = math.hypot(x - 23.5, y - 23.5)
            lit = math.cos(math.atan2(y - 23.5, x - 23.5) + math.pi * 0.75)
            if 21.6 < distance <= 23.4:
                gold.putpixel((x, y), GOLD_DEEP)
            elif 19.4 < distance <= 21.6:
                gold.putpixel((x, y), GOLD_SHINE if lit > 0.7 else GOLD if lit > -0.3 else GOLD_DARK)
            elif 18.4 < distance <= 19.4:
                gold.putpixel((x, y), GOLD_DEEP)
    save(gold, "wheel_node_gold")

    # an arrow head pointing up; drawn rotated around the hub
    pointer = new(15, 12)
    rows = ["       #       ",
            "      #+#      ",
            "     #++o#     ",
            "    #+++oo#    ",
            "   #++++ooo#   ",
            "  #+++++oooo#  ",
            " #++++++ooooo# ",
            "#+++++++oooooo#",
            "###############"]
    shades = {"#": COPPER_DEEP, "+": COPPER_SHINE, "o": COPPER}
    for y, row in enumerate(rows):
        for x, char in enumerate(row):
            if char in shades:
                pointer.putpixel((x, y + 1), shades[char])
    save(pointer, "wheel_pointer")


def make_glow():
    # white, so the game can tint it; only the alpha carries the shape
    image = new(64, 64)
    for y in range(64):
        for x in range(64):
            distance = math.hypot(x - 31.5, y - 31.5) / 31.5
            if distance < 1.0:
                image.putpixel((x, y), (255, 255, 255, int(255 * (1.0 - distance) ** 2 * 0.85)))
    save(image, "glow")

    spark = new(9, 9)
    for i in range(9):
        strength = 255 - abs(i - 4) * 55
        spark.putpixel((i, 4), (255, 255, 255, strength))
        spark.putpixel((4, i), (255, 255, 255, strength))
    for dx, dy in ((3, 3), (5, 3), (3, 5), (5, 5)):
        spark.putpixel((dx, dy), (255, 255, 255, 110))
    save(spark, "spark")


# --- small things -------------------------------------------------------------------------------------

def from_rows(rows, shades, name):
    image = new(len(rows[0]), len(rows))
    for y, row in enumerate(rows):
        for x, char in enumerate(row):
            if char in shades:
                image.putpixel((x, y), shades[char])
    save(image, name)


def make_small():
    from_rows([" #### ",
               "#+ooo#",
               "#o***#",
               "#o***#",
               "#o**-#",
               " #### "], {"#": GREEN_DARK, "+": GREEN_SHINE, "o": GREEN, "*": GREEN, "-": GREEN_DARK}, "gem_green")
    from_rows(["   ####    ",
               "  #+ooo#   ",
               " #+#  #o#  ",
               " #o#  #o#  ",
               "###########",
               "#+ggggggg-#",
               "#gggg#ggg-#",
               "#ggg###gg-#",
               "#gggg#ggg-#",
               "#gggg#ggg-#",
               "#g-------##",
               "########## "], {"#": rgb("241a08"), "+": STEEL_LIGHT, "o": STEEL, "g": GOLD, "-": GOLD_DARK}, "lock")
    from_rows(["      ####      ",
               "   ## #++# ##   ",
               "  #+###++###+#  ",
               " #+++++++++++o# ",
               "  #++oo##oo+o#  ",
               " ##+oo#  #oo+## ",
               "#++++#    #+ooo#",
               "#+++o#    #oooo#",
               "#+ooo#    #oooo#",
               "#oooo#    #ooo-#",
               " ##ooo#  #oo-## ",
               "  #oooo##ooo-#  ",
               " #ooooooooooo-# ",
               "  #o###oo###-#  ",
               "   ## #o-# ##   ",
               "      ####      "], {"#": COPPER_DEEP, "+": COPPER_SHINE, "o": COPPER_LIGHT, "-": COPPER_DARK}, "icon/gear")
    # a key of a keyboard, for the "keys" tab of the settings
    from_rows(["                ",
               "  ############  ",
               " #++++++++++++# ",
               " #+oooooooooo-# ",
               " #+oowwooowwo-# ",
               " #+oowwoowwoo-# ",
               " #+oowwwwwooo-# ",
               " #+oowwwwwooo-# ",
               " #+oowwoowwoo-# ",
               " #+oowwooowwo-# ",
               " #+oooooooooo-# ",
               " #------------# ",
               " #============# ",
               " #============# ",
               "  ############  ",
               "                "], {"#": rgb("1c1a20"), "+": STEEL_LIGHT, "o": STEEL, "w": rgb("2a2530"),
                                     "-": STEEL_DARK, "=": rgb("24222a")}, "icon/keys")
    make_wheel_icon()


def make_wheel_icon():
    """The pet wheel in 16 pixels: a copper ring, a pointer and a gold hub - for its tab in the settings."""
    image = new(16, 16)
    for y in range(16):
        for x in range(16):
            distance = math.hypot(x - 7.5, y - 7.5)
            lit = math.cos(math.atan2(y - 7.5, x - 7.5) + math.pi * 0.75)
            if 6.9 < distance <= 7.9:
                image.putpixel((x, y), COPPER_DEEP)
            elif 5.2 < distance <= 6.9:
                image.putpixel((x, y), COPPER_SHINE if lit > 0.6 else COPPER_LIGHT if lit > -0.2 else COPPER_DARK)
            elif 4.3 < distance <= 5.2:
                image.putpixel((x, y), COPPER_DEEP)
            elif distance <= 4.3:
                image.putpixel((x, y), SLOT)
    for x, y in ((9, 6), (10, 5), (11, 4), (10, 4), (11, 5)):                 # the pointer, up and to the right
        image.putpixel((x, y), GOLD_SHINE)
    for x, y in ((7, 7), (8, 7), (7, 8), (8, 8)):                             # the hub
        image.putpixel((x, y), GOLD)
    image.putpixel((8, 8), GOLD_DARK)
    save(image, "icon/wheel")


def make_scene():
    """The backdrop of the preview: a bit of landscape for the slot bar to float over."""
    width, height = 256, 144
    image = new(width, height)
    sky_top, sky_low = rgb("6fa8e8"), rgb("cfe6f7")
    horizon = 92
    for y in range(height):
        t = min(1.0, y / horizon)
        colour = tuple(int(sky_top[i] + (sky_low[i] - sky_top[i]) * t) for i in range(3)) + (255,)
        rect(image, 0, y, width, y + 1, colour)
    for cx, cy, w in ((40, 22, 30), (150, 14, 44), (212, 34, 26)):          # blocky clouds
        rect(image, cx, cy, cx + w, cy + 5, rgb("ffffff", 235))
        rect(image, cx + 5, cy - 4, cx + w - 8, cy, rgb("ffffff", 235))
    far, near, grass, soil = rgb("7fae8f"), rgb("5f9a5a"), rgb("6cbf4a"), rgb("7a5a3a")
    for x in range(width):
        far_y = int(horizon - 14 - 8 * math.sin(x / 31.0) - 5 * math.sin(x / 11.0 + 1.3))
        rect(image, x, far_y, x + 1, height, far)
        near_y = int(horizon - 2 - 6 * math.sin(x / 23.0 + 2.1) - 3 * math.sin(x / 7.0))
        rect(image, x, near_y, x + 1, height, near)
    rect(image, 0, horizon + 8, width, height, grass)
    rect(image, 0, horizon + 12, width, height, soil)
    for x in range(0, width, 4):                                              # a ragged grass edge
        rect(image, x, horizon + 8 + (x * 7 % 3), x + 2, horizon + 13, grass)
    for x in range(0, width, 16):                                             # block seams in the soil
        rect(image, x, horizon + 12, x + 1, height, rgb("6a4c30"))
    save(image, "scene")


# --- taken from the artwork ---------------------------------------------------------------------------

ICONS = {
    "icon/close": "BetterPets_Fixed_Icons/16x16/close.png",
    "icon/back": "BetterPets_UI_Assets (1)/icons_16x16/back.png",
    "icon/next": "BetterPets_UI_Assets (1)/icons_16x16/next_page.png",
    "icon/previous": "BetterPets_UI_Assets (1)/icons_16x16/previous_page.png",
    "icon/put_away": "BetterPets_UI_Assets (1)/icons_16x16/despawn.png",
    "icon/eye_on": "BetterPets_Fixed_Icons/16x16/visibility_on.png",
    "icon/eye_off": "BetterPets_Fixed_Icons/16x16/visibility_off.png",
    "icon/search": "BetterPets_Extra_Icons/icons_16x16/filter_sort.png",
    "icon/empty": "BetterPets_Extra_Icons/icons_16x16/trade_empty_offer.png",
    "icon/star": "BetterPets_Extra_Icons/icons_16x16/ascension_reached.png",
    "icon/locked": "BetterPets_Extra_Icons/icons_16x16/ascension_locked.png",
    "icon/check": "BetterPets_Extra_Icons/icons_16x16/trade_confirm.png",
    "icon/cancel": "BetterPets_Extra_Icons/icons_16x16/trade_cancel.png",
    "icon/menu": "BetterPets_UI_Assets (1)/icons_16x16/catalogue.png",
    "icon/fox": "BetterPets_UI_Assets (1)/icons_16x16/fox_pet.png",
    "icon/cat": "BetterPets_UI_Assets (1)/icons_16x16/cat_pet.png",
    "icon/coin": "BetterPets_Extra_Icons/icons_16x16/token_balance.png",
    "icon/skin": "BetterPets_Extra_Icons/icons_16x16/shop_skins.png",
    "icon/rank": "BetterPets_Extra_Icons/icons_16x16/your_rank.png",
}

CHARACTERS = {
    "character/fox": "main_left.png",
    "character/otter": "main_right.png",
    "character/cat": "catalogue_left.png",
    "character/owl": "catalogue_right.png",
    "character/dragon": "customization_right.png",
    "character/moon_fox": "ascension_left.png",
    "character/star_dragon": "ascension_right.png",
}


def copy_artwork():
    for name, source in ICONS.items():
        path = os.path.join(ART, source)
        if os.path.exists(path):
            icon = Image.open(path).convert("RGBA")
        else:
            # a few icons only exist at 32x32; every second pixel gives the 16x16 version
            icon = Image.open(path.replace("16x16", "32x32")).convert("RGBA").resize((16, 16), Image.NEAREST)
        save(icon, name)
    for name, source in CHARACTERS.items():
        save(Image.open(os.path.join(ART, "BetterPets_UI_Assets (1)", "characters", source)).convert("RGBA"), name)


def main():
    if os.path.isdir(OUT):
        shutil.rmtree(OUT)
    make_panel()
    make_tooltip()
    make_inset()
    make_slot()
    make_gold_frame()
    make_card("card", rgb("3f2e24"), rgb("4d392c"), BEVEL_DARK)
    make_card("card_hover", rgb("4a362a"), rgb("5c4534"), BEVEL_LIGHT)
    make_button("button", rgb("4a3528"), rgb("5a4232"), COPPER_DARK, COPPER)
    make_button("button_hover", rgb("5a4030"), rgb("70523c"), COPPER, COPPER_SHINE)
    make_button("button_disabled", rgb("2f2620"), rgb("3a2f28"), rgb("4a3c32"), rgb("5a4a3e"))
    make_tab("tab", rgb("4a3528"), BEVEL_DARK, None)
    make_tab("tab_hover", rgb("5a4030"), BEVEL_LIGHT, None)
    make_tab("tab_active", rgb("5e4433"), COPPER, GOLD)
    make_toggle()
    make_slider()
    make_key_cap()
    make_wheel()
    make_glow()
    make_small()
    make_scene()
    copy_artwork()
    count = sum(len([f for f in files if f.endswith(".png")]) for _, _, files in os.walk(OUT))
    print(f"wrote {count} sprites to {os.path.normpath(OUT)}")


if __name__ == "__main__":
    main()
