#!/usr/bin/env python3
"""
Patches FlatLaf's FlatDarkLaf.properties inside the FlatLaf JAR with the
Breeze Dark colour palette so FLATLAF_DARK looks like JD Plain Dark out of
the box — no user-visible install dialog, no separate theme selection.

Usage: patch-flatlaf.py <src.jar> <dst.jar>
"""
import io, os, sys, zipfile

TARGET = "com/formdev/flatlaf/FlatDarkLaf.properties"

# Breeze Dark palette — KDE Breeze Dark / matching JD_Plain_Dark theme.json
# Explicit per-component overrides because JD's ExtTable (download list, link
# grabber) reads Table.background from UIManager — the @background variable
# alone is not enough, FlatLaf needs the component-specific keys too.
BREEZE_DARK = b"""
# ===== JD Plain Dark \xe2\x80\x94 Breeze Dark palette =====
# Master variables
@accentColor=#3daee9
@background=#232629
@foreground=#eff0f1
@disabledForeground=#7f8c8d
@selectionBackground=#3daee9
@selectionForeground=#eff0f1
@selectionInactiveBackground=#2d3237
@menuBackground=#1b1e20
@toolbarBackground=#232629
@tooltipBackground=#31363b
@tooltipForeground=#eff0f1
@borderColor=#2d3237
@separatorColor=#2d3237
@linkColor=#2980b9
@focusColor=#3daee9
@componentBackground=#31363b
@disabledBackground=#2d3237

# ===== Explicit Swing component overrides (JD ExtTable reads these) =====
Table.background=#232629
Table.foreground=#eff0f1
Table.alternateRowColor=#1e2124
Table.gridColor=#2d3237
Table.selectionBackground=#3daee9
Table.selectionForeground=#eff0f1
Table.selectionInactiveBackground=#2d3237
Table.focusCellHighlightBorder=2,2,2,2,#3daee9
TableHeader.background=#1b1e20
TableHeader.foreground=#eff0f1
TableHeader.separatorColor=#2d3237

List.background=#232629
List.foreground=#eff0f1
List.selectionBackground=#3daee9
List.selectionForeground=#eff0f1
List.selectionInactiveBackground=#2d3237

Tree.background=#232629
Tree.foreground=#eff0f1
Tree.selectionBackground=#3daee9
Tree.selectionForeground=#eff0f1
Tree.selectionInactiveBackground=#2d3237

Panel.background=#232629
Panel.foreground=#eff0f1
Viewport.background=#232629
Viewport.foreground=#eff0f1
ScrollPane.background=#232629
SplitPane.background=#232629
SplitPaneDivider.background=#1b1e20

TextField.background=#31363b
TextField.foreground=#eff0f1
TextArea.background=#31363b
TextArea.foreground=#eff0f1
EditorPane.background=#31363b
EditorPane.foreground=#eff0f1
PasswordField.background=#31363b
PasswordField.foreground=#eff0f1
FormattedTextField.background=#31363b
FormattedTextField.foreground=#eff0f1

TabbedPane.background=#232629
TabbedPane.foreground=#eff0f1
TabbedPane.selectedBackground=#31363b
TabbedPane.hoverColor=#31363b

ToolBar.background=#232629
ToolBar.foreground=#eff0f1
MenuBar.background=#1b1e20
MenuBar.foreground=#eff0f1
Menu.background=#1b1e20
Menu.foreground=#eff0f1
MenuItem.background=#1b1e20
MenuItem.foreground=#eff0f1
PopupMenu.background=#1b1e20
PopupMenu.foreground=#eff0f1

Button.background=#31363b
Button.foreground=#eff0f1
Button.hoverBackground=#3daee9
ToggleButton.background=#31363b
ToggleButton.foreground=#eff0f1
ComboBox.background=#31363b
ComboBox.foreground=#eff0f1
CheckBox.background=#232629
CheckBox.foreground=#eff0f1
RadioButton.background=#232629
RadioButton.foreground=#eff0f1

ProgressBar.background=#31363b
ProgressBar.foreground=#2d8a42
ProgressBar.selectionBackground=#eff0f1
ProgressBar.selectionForeground=#eff0f1

OptionPane.background=#232629
OptionPane.foreground=#eff0f1
RootPane.background=#232629
Frame.background=#232629
Dialog.background=#232629

# JD-specific keys (custom JD renderers may check for these)
backgroundColor=#232629
foregroundColor=#eff0f1
alternateRowBackground=#1e2124
tableBackground=#232629
panelBackground=#31363b
"""


def patch(src: str, dst: str) -> None:
    buf = io.BytesIO()
    patched = False
    with zipfile.ZipFile(src, "r") as zin, \
         zipfile.ZipFile(buf, "w", zipfile.ZIP_DEFLATED) as zout:
        for item in zin.infolist():
            data = zin.read(item.filename)
            if item.filename == TARGET:
                data += BREEZE_DARK
                patched = True
                print(f"[flatlaf-patch] Patched  {TARGET}")
            zout.writestr(item, data)
        if not patched:
            # FlatLaf version without this file — create it
            zout.writestr(TARGET, BREEZE_DARK.lstrip())
            print(f"[flatlaf-patch] Created  {TARGET} (was absent)")

    os.makedirs(os.path.dirname(os.path.abspath(dst)), exist_ok=True)
    with open(dst, "wb") as f:
        f.write(buf.getvalue())
    print(f"[flatlaf-patch] Written  {dst}")


if __name__ == "__main__":
    if len(sys.argv) != 3:
        sys.exit(f"Usage: {sys.argv[0]} <src.jar> <dst.jar>")
    patch(sys.argv[1], sys.argv[2])
