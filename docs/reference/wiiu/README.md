# Wii U Hardware Reference (Espresso CPU / Latte GPU)

Primary-source documentation for the hardware Cemu emulates, so behaviour questions
can be answered from a manual instead of from inference about what a game "probably"
expects. The PDFs are **gitignored** (`docs/reference/**/*.pdf`); only this README is
tracked. Re-fetch with the URLs below.

| file | what | why it matters here | pages |
|---|---|---|---|
| `ppc-750cl-users-manual.pdf` | IBM PowerPC 750CL User's Manual | **Espresso is a 750CL derivative.** The authoritative reference for the guest CPU. | ~700 |
| `gekko-users-manual.pdf` | IBM Gekko User's Manual | Gekko (GameCube) documents the **paired-single** unit in more detail than the 750CL manual, including the quantised load/store tables. | ~450 |
| `r700-family-instruction-set-architecture.pdf` | AMD R700-Family ISA Reference | **Latte is R700-derived.** Shader instruction semantics for the Latte shader decompiler. | 392 |
| `r6xx-r7xx-3d-register-reference.pdf` | Radeon R6xx/R7xx 3D Register Reference | The actual register definitions behind Latte's command-buffer registers. | ~500 |

Sources:
- 750CL: `https://fail0verflow.com/media/files/ppc_750cl.pdf`
- Gekko: `https://datasheets.chipdb.org/IBM/PowerPC/Gekko/gekko_user_manual.pdf`
- R700 ISA: `https://www.x.org/docs/AMD/old/R700-Family_Instruction_Set_Architecture.pdf`
- R6xx/R7xx registers: `https://www.x.org/docs/AMD/old/R6xx_3D_Registers.pdf`

## Why both PowerPC manuals

Espresso is tri-core, out-of-order-free, 32-bit PowerPC with a **paired-single** FPU and
**no VMX/AltiVec**. That last point is the single most important fact when porting
optimisation ideas from other emulators: RPCS3 (Cell) and Xenia (Xenon) both emulate
PowerPC *with* AltiVec, so their vector work has no counterpart here. See
`docs/research/20260820-rpcs3-arm64-optimizations-for-cemu.md`.

The 750CL manual is the correct reference for the core, cache, and integer/FP behaviour.
The Gekko manual covers the paired-single extension - `ps_*` arithmetic, and the
`psq_l`/`psq_st` quantised load/store with their GQR scale/type encodings - which is the
part of the guest ISA most likely to be emitted subtly wrong.

## Reading them

The `Read` tool cannot render these PDFs. Use pypdf:

```python
import pypdf
r = pypdf.PdfReader("docs/reference/wiiu/gekko-users-manual.pdf")
print(r.pages[123].extract_text())   # 0-indexed
```

## Related

`docs/reference/arm/` holds the host-side counterparts (Arm ARM plus the Cortex
X3/A715/A710/A510 optimisation guides). A correctness question about a guest instruction
is answered by the manuals here; a question about how fast the host executes the
translation is answered there. Keep the two straight - neither answers the other's
question.
