# The Mac installer window (dmgbuild settings): TapDrop on the left, an arrow, Applications on the right, on
# TapDrop's own background. scripts/release.sh builds it, from the repository root:
#   dmgbuild -s mac/dmg/settings.py -D app=mac/build/TapDrop.app TapDrop release/TapDrop-<version>-mac.dmg
# The background (640 × 428, and @2x) is background.html rendered to PNG; release.sh joins the two into one TIFF.
# The window is the background plus Finder's title bar; the bottom 28 px stay clear in case Finder shows its path bar.
import os.path

app = defines.get('app', 'mac/build/TapDrop.app')  # noqa: F821 (dmgbuild provides `defines`)
appname = os.path.basename(app)

format = 'ULFO'
filesystem = 'HFS+'
files = [app]
symlinks = {'Applications': '/Applications'}
icon = 'mac/Resources/AppIcon.icns'
background = defines.get('background', 'mac/dmg/background.tiff')  # noqa: F821

show_status_bar = False
show_tab_view = False
show_toolbar = False
show_pathbar = False
show_sidebar = False
window_rect = ((240, 160), (640, 460))
default_view = 'icon-view'
show_icon_preview = False
arrange_by = None
label_pos = 'bottom'
text_size = 13
icon_size = 104
icon_locations = {
    appname: (170, 196),
    'Applications': (470, 196),
}
