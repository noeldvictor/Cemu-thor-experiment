[StarFoxZero_InfiniteLife_USAv16]
moduleMatches = 0x33864358

# Safe target from the prj_030 USA v16 module dump:
# 0x024F6A44: subf r0, r4, r0      ; new shield/life = current - damage
# 0x024F6A4C: sth r0, 0x28(r29)    ; commit current shield/life
#
# NOP only the commit store. This preserves damage events, hit reactions, warning UI,
# and mission scripts while preventing the player's current shield/life from dropping.
0x024F6A4C = nop
