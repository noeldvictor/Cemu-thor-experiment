[StarFoxZero_SuperShot_InfiniteBombs_USAv16]
moduleMatches = 0x33864358

# Known Star Fox Zero v16 bomb decrement:
# 0x024FAB20: beq 0x024FAB2C
# 0x024FAB24: addi r8, r7, -1
# 0x024FAB28: stw r8, 0x1c(r12)
0x024FAB28 = nop
