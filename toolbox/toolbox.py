"""Toolbox met 4 vakken (2x2) van 100 x 120 mm, 60 mm hoog, met klikdeksel.

Uitvoeren:  python toolbox.py   (vereist: pip install build123d)
Maakt toolbox_bak.step/.stl en toolbox_deksel.step/.stl in deze map.

Klikprincipe: de deksel heeft een rand (lip) die in de bak valt. Op de lip
zitten ribbels die in een groef aan de binnenkant van de bakwand klikken.
"""
from pathlib import Path
from build123d import *

# --- Maten (mm) ---------------------------------------------------------
VAK_X, VAK_Y = 100, 120      # binnenmaat per vak
HOOGTE = 60                  # totale hoogte bak
WAND = 2.5                   # buitenwand
BODEM = 2.5                  # bodemdikte
TUSSEN = 2.0                 # tussenschotten
HOEK_R = 4                   # afronding buitenhoeken

DEKSEL_DIK = 2.5             # dikte dekselplaat
LIP_H = 8                    # hoe diep de lip in de bak steekt
LIP_DIK = 1.6                # wanddikte lip (dun = verend)
SPELING = 0.25               # speling lip <-> bakwand per kant
KLIK_Z = 4                   # hoogte ribbel/groef onder bovenrand
RIBBEL_R = 0.8               # straal ribbel op lip
GROEF_R = 1.0                # straal groef in bakwand
RIBBEL_LEN = 60              # lengte van elk ribbelsegment

BIN_X = 2 * VAK_X + TUSSEN
BIN_Y = 2 * VAK_Y + TUSSEN
BUI_X = BIN_X + 2 * WAND
BUI_Y = BIN_Y + 2 * WAND
SCHOT_H = HOOGTE - LIP_H - 2  # schotten lager zodat de lip erover past

# --- Bak ----------------------------------------------------------------
with BuildPart() as bak:
    with BuildSketch():
        RectangleRounded(BUI_X, BUI_Y, HOEK_R)
    extrude(amount=HOOGTE)
    # holte
    with BuildSketch(Plane.XY.offset(BODEM)):
        Rectangle(BIN_X, BIN_Y)
    extrude(amount=HOOGTE, mode=Mode.SUBTRACT)
    # tussenschotten (kruis)
    with BuildSketch(Plane.XY.offset(BODEM)):
        Rectangle(TUSSEN, BIN_Y)
        Rectangle(BIN_X, TUSSEN)
    extrude(amount=SCHOT_H - BODEM)
    # rondlopende klikgroef aan binnenkant wand
    zg = HOOGTE - KLIK_Z
    for lengte, as_x, offset in [
        (BIN_X, True, BIN_Y / 2), (BIN_X, True, -BIN_Y / 2),
        (BIN_Y, False, BIN_X / 2), (BIN_Y, False, -BIN_X / 2),
    ]:
        rot = (0, 90, 0) if as_x else (90, 0, 0)
        pos = (0, offset, zg) if as_x else (offset, 0, zg)
        with Locations(Location(pos, rot)):
            Cylinder(GROEF_R, lengte, mode=Mode.SUBTRACT)
    # duimuitsparing aan voorkant om de deksel open te wippen
    with Locations((0, -BUI_Y / 2, HOOGTE)):
        Box(30, WAND * 3, 3, mode=Mode.SUBTRACT)

# --- Deksel (gemodelleerd ondersteboven: plaat op z=0, lip omhoog) ------
LIP_X = BIN_X - 2 * SPELING
LIP_Y = BIN_Y - 2 * SPELING
with BuildPart() as deksel:
    with BuildSketch():
        RectangleRounded(BUI_X, BUI_Y, HOEK_R)
    extrude(amount=DEKSEL_DIK)
    with BuildSketch(Plane.XY.offset(DEKSEL_DIK)):
        Rectangle(LIP_X, LIP_Y)
        Rectangle(LIP_X - 2 * LIP_DIK, LIP_Y - 2 * LIP_DIK, mode=Mode.SUBTRACT)
    extrude(amount=LIP_H)
    # ribbels op buitenkant lip, ter hoogte van de groef
    zr = DEKSEL_DIK + KLIK_Z
    for as_x, offset in [(True, LIP_Y / 2), (True, -LIP_Y / 2),
                         (False, LIP_X / 2), (False, -LIP_X / 2)]:
        lengte = RIBBEL_LEN
        rot = (0, 90, 0) if as_x else (90, 0, 0)
        pos = (0, offset, zr) if as_x else (offset, 0, zr)
        with Locations(Location(pos, rot)):
            Cylinder(RIBBEL_R, lengte)
    # ribbels mogen niet naar binnen uitsteken
    with BuildSketch(Plane.XY.offset(DEKSEL_DIK)):
        Rectangle(LIP_X - 2 * LIP_DIK, LIP_Y - 2 * LIP_DIK)
    extrude(amount=LIP_H, mode=Mode.SUBTRACT)

uit = Path(__file__).parent
for naam, deel in [("toolbox_bak", bak.part), ("toolbox_deksel", deksel.part)]:
    export_step(deel, str(uit / f"{naam}.step"))
    export_stl(deel, str(uit / f"{naam}.stl"))
    bb = deel.bounding_box()
    print(f"{naam}: {bb.size.X:.1f} x {bb.size.Y:.1f} x {bb.size.Z:.1f} mm, "
          f"volume {deel.volume / 1000:.1f} cm3, geldig={deel.is_valid}")

# Samenstelling ter controle: deksel omgedraaid op de bak
dicht = deksel.part.rotate(Axis.X, 180).translate((0, 0, HOOGTE + DEKSEL_DIK))
overlap = bak.part & dicht
print(f"overlap ribbel/groef (moet ~0 zijn bij juiste klik): {overlap.volume:.2f} mm3")
export_step(Compound([bak.part, dicht]), str(uit / "toolbox_samenstelling.step"))
